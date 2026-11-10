//! The JNI surface of `app.dibe.android.engine.NativeEngine`. It is a Kotlin `object`, so its methods
//! are instance methods and each export takes the object as `this`. Handles are `Box::into_raw`
//! pointers. Every error and panic reaches Kotlin as a `java.lang.RuntimeException` carrying the
//! Rust error chain; the calls are made from one thread at a time (TranscriptionEngine's).

use std::sync::Mutex;
use std::time::Instant;

use anyhow::Context as _;
use dibe_core::engine::{self, Engine, EngineCapabilities};
use dibe_core::live::{LiveCore, LiveEvent};
use dibe_core::resample::Resampler;
use jni::elements::ReleaseMode;
use jni::errors::ErrorPolicy;
use jni::objects::{JFloatArray, JObject, JString};
use jni::refs::Global;
use jni::strings::JNIString;
use jni::sys::{jboolean, jint, jlong};
use jni::vm::JavaVM;
use jni::{jni_sig, jni_str, Env, EnvUnowned, JValue};
use serde::Serialize;
use whisper_rs::{ContextOptions, Segment, StreamCallbacks, TranscribeOptions};

/// A loaded engine and the VAD file it and its live sessions cut speech with.
pub struct Model {
    engine: Engine,
    vad_path: String,
}

impl Model {
    pub fn load(model_path: &str, vad_path: &str) -> anyhow::Result<Self> {
        let mut engine = Engine::load(model_path, ContextOptions::default())?;
        // Loaded with the model rather than on the first transcription, so a bad VAD file fails here.
        if let Engine::Parakeet { vad, .. } | Engine::Nemotron { vad, .. } = &mut engine {
            let loaded = vad_rs::Vad::new(vad_path, vad_rs::Options::default()).context("cannot load the VAD model")?;
            *vad = Some((vad_path.to_owned(), loaded));
        }
        Ok(Self {
            engine,
            vad_path: vad_path.to_owned(),
        })
    }

    fn options(&self, language: &str) -> TranscribeOptions {
        TranscribeOptions {
            language: Some(engine_language(&self.engine.capabilities(), language).to_owned()),
            vad_model_path: Some(self.vad_path.clone()),
            ..TranscribeOptions::default()
        }
    }

    pub fn transcribe(&mut self, pcm: &[f32], language: &str, callbacks: StreamCallbacks<'_>) -> anyhow::Result<Vec<Segment>> {
        let options = self.options(language);
        Ok(self.engine.transcribe_stream(pcm, options, callbacks)?.segments)
    }

    /// A live session with its own VAD, as vibe-server's: the engine's is busy inside each inference.
    pub fn live(&self, language: &str, partials: bool) -> anyhow::Result<Live> {
        let vad = vad_rs::Vad::new(&self.vad_path, vad_rs::Options::default()).context("cannot load the VAD model")?;
        Ok(Live {
            core: LiveCore::new(None, self.engine.capabilities().text_prompts, partials),
            vad,
            options: self.options(language),
        })
    }
}

/// Parakeet and Nemotron get "auto" for a language the model does not list; Whisper detects on "auto".
fn engine_language<'a>(capabilities: &EngineCapabilities, language: &'a str) -> &'a str {
    if capabilities.requires_vad && !capabilities.languages.iter().any(|code| code == language) {
        "auto"
    } else {
        language
    }
}

pub struct Live {
    core: LiveCore,
    vad: vad_rs::Vad,
    options: TranscribeOptions,
}

impl Live {
    pub fn push(&mut self, pcm: &[f32]) {
        self.core.push(pcm);
    }

    pub fn advance(&mut self, model: &mut Model) -> anyhow::Result<Vec<LiveEvent>> {
        let Self { core, vad, options } = self;
        core.advance(
            Instant::now(),
            |samples| Ok(vad.speech(samples)?),
            infer(&mut model.engine, options),
        )
    }

    pub fn finish(self, model: &mut Model) -> anyhow::Result<Vec<LiveEvent>> {
        let Self { core, mut vad, options } = self;
        core.finish(|samples| Ok(vad.speech(samples)?), infer(&mut model.engine, &options))
    }
}

fn infer<'a>(
    engine: &'a mut Engine,
    options: &'a TranscribeOptions,
) -> impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>> + 'a {
    move |samples, prompt| {
        let options = TranscribeOptions {
            prompt: prompt.map(str::to_owned),
            ..options.clone()
        };
        Ok(engine.transcribe(samples, options)?.segments)
    }
}

/// The desktop transcript's segment: centiseconds, `stop` rather than `end`.
pub fn segments_json(segments: &[Segment]) -> anyhow::Result<String> {
    #[derive(Serialize)]
    struct Json<'a> {
        start: i64,
        stop: i64,
        text: &'a str,
    }
    let segments: Vec<_> = segments
        .iter()
        .map(|segment| Json {
            start: segment.start,
            stop: segment.end,
            text: &segment.text,
        })
        .collect();
    Ok(serde_json::to_string(&segments)?)
}

/// Calls a Kotlin `NativeCallbacks` from inside the engine. Once a call fails no further one is made
/// and the engine is told to abort; [`Callbacks::rethrow`] then hands Kotlin what the callback threw.
struct Callbacks {
    vm: JavaVM,
    target: Global<JObject<'static>>,
    failure: Mutex<Option<jni::errors::Error>>,
}

impl Callbacks {
    fn new(env: &mut Env, target: &JObject) -> anyhow::Result<Self> {
        Ok(Self {
            vm: env.get_java_vm()?,
            target: env.new_global_ref(target)?,
            failure: Mutex::new(None),
        })
    }

    fn stream(&self) -> StreamCallbacks<'_> {
        StreamCallbacks {
            on_progress: Some(Box::new(|percent| {
                self.call(|env, target| {
                    env.call_method(target, jni_str!("onProgress"), jni_sig!("(I)V"), &[JValue::Int(percent)])?;
                    Ok(())
                });
            })),
            on_segment: Some(Box::new(|segment| {
                self.call(|env, target| {
                    let text = env.new_string(&segment.text)?;
                    let args = [JValue::Long(segment.start), JValue::Long(segment.end), JValue::Object(&text)];
                    env.call_method(target, jni_str!("onSegment"), jni_sig!("(JJLjava/lang/String;)V"), &args)?;
                    Ok(())
                });
            })),
            should_abort: Some(Box::new(|| {
                self.call(|env, target| env.call_method(target, jni_str!("shouldAbort"), jni_sig!("()Z"), &[])?.z())
                    .unwrap_or(true)
            })),
        }
    }

    fn call<T>(&self, call: impl FnOnce(&mut Env, &JObject) -> jni::errors::Result<T>) -> Option<T> {
        if self.failure.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).is_some() {
            return None;
        }
        // The engine calls back on the thread that called it, which the JVM already attached.
        let result = self.vm.attach_current_thread(|env| call(env, &self.target));
        result
            .map_err(|err| *self.failure.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(err))
            .ok()
    }

    /// The callback's own exception, not a wrapper: a `CancellationException` must stay one. The
    /// attachment caught and cleared it, so it is thrown again here.
    fn rethrow(self, env: &mut Env) -> anyhow::Result<()> {
        let Some(err) = self.failure.into_inner().unwrap_or_else(|poisoned| poisoned.into_inner()) else {
            return Ok(());
        };
        if let jni::errors::Error::CaughtJavaException { exception, .. } = &err {
            // Err(JavaException) is the success case here: the exception is now pending.
            let _ = env.throw(exception);
        }
        Err(anyhow::Error::from(err).context("a NativeCallbacks call failed"))
    }
}

/// Throws the error chain (or the panic message) as a `RuntimeException`, unless a callback's
/// exception is already pending: that one is the cause, and it reaches Kotlin unchanged.
struct Throw;

impl<T: Default> ErrorPolicy<T, anyhow::Error> for Throw {
    type Captures<'unowned_env_local: 'native_method, 'native_method> = ();

    fn on_error<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut (),
        err: anyhow::Error,
    ) -> jni::errors::Result<T> {
        throw(env, format!("{err:#}"));
        Ok(T::default())
    }

    fn on_panic<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut (),
        payload: Box<dyn std::any::Any + Send + 'static>,
    ) -> jni::errors::Result<T> {
        let message = payload
            .downcast_ref::<&str>()
            .map(|message| message.to_string())
            .or_else(|| payload.downcast_ref::<String>().cloned())
            .unwrap_or_default();
        throw(env, format!("native engine panicked: {message}"));
        Ok(T::default())
    }
}

fn throw(env: &mut Env, message: String) {
    if !env.exception_check() {
        // Err(JavaException) is the success case here: the exception is now pending.
        let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), JNIString::from(message));
    }
}

/// # Safety
/// `handle` is 0 or came from `Box::into_raw` of a `T` that is still alive.
unsafe fn borrow<'a, T>(handle: jlong, what: &str) -> anyhow::Result<&'a mut T> {
    unsafe { (handle as *mut T).as_mut() }.with_context(|| format!("no {what}"))
}

/// # Safety
/// As [`borrow`], and the handle is not used again.
unsafe fn take<T>(handle: jlong, what: &str) -> anyhow::Result<Box<T>> {
    anyhow::ensure!(handle != 0, "no {what}");
    Ok(unsafe { Box::from_raw(handle as *mut T) })
}

fn floats(env: &Env, array: &JFloatArray) -> anyhow::Result<Vec<f32>> {
    let mut samples = vec![0.0; array.len(env)?];
    array.get_region(env, 0, &mut samples)?;
    Ok(samples)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_metadata<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    model_path: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> anyhow::Result<_> {
        let capabilities = engine::metadata(&model_path.try_to_string(env)?).map_err(anyhow::Error::msg)?;
        Ok(env.new_string(serde_json::to_string(&capabilities)?)?)
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_load<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    model_path: JString<'local>,
    vad_path: JString<'local>,
) -> jlong {
    env.with_env(|env| -> anyhow::Result<_> {
        let model = Model::load(&model_path.try_to_string(env)?, &vad_path.try_to_string(env)?)?;
        Ok(Box::into_raw(Box::new(model)) as jlong)
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_unload<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
) {
    env.with_env(|_| -> anyhow::Result<_> {
        drop(unsafe { take::<Model>(handle, "model loaded") }?);
        Ok(())
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_transcribe<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    pcm: JFloatArray<'local>,
    language: JString<'local>,
    callbacks: JObject<'local>,
) -> JString<'local> {
    env.with_env(|env| -> anyhow::Result<_> {
        let model = unsafe { borrow::<Model>(handle, "model loaded") }?;
        // In place where the VM allows it (ART does for large arrays): a window is tens of MB.
        // Safety: TranscriptionEngine hands over an array that nothing writes during the call.
        let pcm = unsafe { pcm.get_elements(env, ReleaseMode::NoCopyBack) }?;
        let language = language.try_to_string(env)?;
        let callbacks = Callbacks::new(env, &callbacks)?;
        let result = model.transcribe(&pcm, &language, callbacks.stream());
        // A callback that threw made the engine abort: its exception is the one to report.
        callbacks.rethrow(env)?;
        Ok(env.new_string(segments_json(&result?)?)?)
    })
    .resolve::<Throw>()
}

fn float_array<'local>(env: &mut Env<'local>, samples: &[f32]) -> anyhow::Result<JFloatArray<'local>> {
    let array = JFloatArray::new(env, samples.len())?;
    array.set_region(env, 0, samples)?;
    Ok(array)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_resamplerNew<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    from_rate: jint,
) -> jlong {
    env.with_env(|_| -> anyhow::Result<_> {
        let from_rate = u32::try_from(from_rate)
            .ok()
            .filter(|rate| *rate > 0)
            .with_context(|| format!("invalid sample rate {from_rate}"))?;
        Ok(Box::into_raw(Box::new(Resampler::new(from_rate))) as jlong)
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_resamplerPush<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    resampler: jlong,
    pcm: JFloatArray<'local>,
) -> JFloatArray<'local> {
    env.with_env(|env| -> anyhow::Result<_> {
        let resampler = unsafe { borrow::<Resampler>(resampler, "resampler") }?;
        let resampled = resampler.push(&floats(env, &pcm)?);
        float_array(env, &resampled)
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_resamplerFinish<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    resampler: jlong,
) -> JFloatArray<'local> {
    env.with_env(|env| -> anyhow::Result<_> {
        let resampler = unsafe { take::<Resampler>(resampler, "resampler") }?;
        float_array(env, &resampler.finish())
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_resamplerDrop<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    resampler: jlong,
) {
    env.with_env(|_| -> anyhow::Result<_> {
        drop(unsafe { take::<Resampler>(resampler, "resampler") }?);
        Ok(())
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_liveStart<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    language: JString<'local>,
    partials: jboolean,
) -> jlong {
    env.with_env(|env| -> anyhow::Result<_> {
        let model = unsafe { borrow::<Model>(handle, "model loaded") }?;
        let live = model.live(&language.try_to_string(env)?, partials)?;
        Ok(Box::into_raw(Box::new(live)) as jlong)
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_livePush<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    live: jlong,
    pcm: JFloatArray<'local>,
) {
    env.with_env(|env| -> anyhow::Result<_> {
        let live = unsafe { borrow::<Live>(live, "live session") }?;
        live.push(&floats(env, &pcm)?);
        Ok(())
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_liveAdvance<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    live: jlong,
) -> JString<'local> {
    env.with_env(|env| -> anyhow::Result<_> {
        let model = unsafe { borrow::<Model>(handle, "model loaded") }?;
        let live = unsafe { borrow::<Live>(live, "live session") }?;
        let events = live.advance(model)?;
        Ok(env.new_string(serde_json::to_string(&events)?)?)
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_liveFinish<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    live: jlong,
) -> JString<'local> {
    env.with_env(|env| -> anyhow::Result<_> {
        // Taken first, so the session is freed even when the rest fails.
        let live = unsafe { take::<Live>(live, "live session") }?;
        let model = unsafe { borrow::<Model>(handle, "model loaded") }?;
        let events = live.finish(model)?;
        Ok(env.new_string(serde_json::to_string(&events)?)?)
    })
    .resolve::<Throw>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_dibe_android_engine_NativeEngine_liveDrop<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    live: jlong,
) {
    env.with_env(|_| -> anyhow::Result<_> {
        drop(unsafe { take::<Live>(live, "live session") }?);
        Ok(())
    })
    .resolve::<Throw>()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unlisted_languages_become_auto_for_the_vad_engines_only() {
        let parakeet = EngineCapabilities {
            engine: "parakeet".into(),
            requires_vad: true,
            languages: vec!["he".into()],
            ..engine::whisper_capabilities()
        };
        assert_eq!(engine_language(&parakeet, "he"), "he");
        assert_eq!(engine_language(&parakeet, "fr"), "auto");
        assert_eq!(engine_language(&parakeet, "auto"), "auto");
        let whisper = engine::whisper_capabilities();
        assert_eq!(engine_language(&whisper, "auto"), "auto");
        assert_eq!(engine_language(&whisper, "xx"), "xx");
    }

    #[test]
    fn segments_and_live_events_have_the_shapes_kotlin_parses() {
        let segments = [Segment {
            start: 40,
            end: 1120,
            text: "שלום".into(),
            no_speech_prob: 0.5,
        }];
        assert_eq!(
            segments_json(&segments).unwrap(),
            r#"[{"start":40,"stop":1120,"text":"שלום"}]"#
        );
        let events = [
            LiveEvent::Final {
                start: 0.5,
                end: 1.25,
                text: "x".into(),
            },
            LiveEvent::Warning {
                code: "vad_failed",
                message: "m".into(),
            },
        ];
        assert_eq!(
            serde_json::to_string(&events).unwrap(),
            r#"[{"type":"final","start":0.5,"end":1.25,"text":"x"},{"type":"warning","code":"vad_failed","message":"m"}]"#
        );
    }
}
