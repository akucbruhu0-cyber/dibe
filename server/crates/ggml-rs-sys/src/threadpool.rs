//! A worker pool for the CPU backend, shared by the engines that compute many small graphs.

use std::ptr;

/// Without OpenMP, ggml spawns and joins its worker threads on every graph it computes. The decoders run
/// hundreds of tiny graphs per chunk, and on Android that thread churn cost more than the math, so the
/// backend gets a pool that lives as long as it does. Other targets link OpenMP, which already keeps
/// its workers, and build ggml in ways where this symbol is not always there. Null when there is no pool.
///
/// # Safety
/// `backend` is a CPU backend. Free the pool with `ggml_threadpool_free` once the backend is freed.
#[cfg(target_os = "android")]
pub unsafe fn attach_threadpool(backend: crate::ggml_backend_t, threads: i32) -> crate::ggml_threadpool_t {
    if threads < 2 {
        return ptr::null_mut();
    }
    let mut params = crate::ggml_threadpool_params_default(threads);
    // ggml's barriers wait for the slowest worker, so one thread on a LITTLE core slows all of them:
    // pin the workers to the fastest cores.
    let cores = fastest_cores(threads as usize, params.cpumask.len());
    if !cores.is_empty() {
        for core in cores {
            params.cpumask[core] = true;
        }
        params.strict_cpu = true;
    }
    let threadpool = crate::ggml_threadpool_new(&mut params);
    if !threadpool.is_null() {
        crate::ggml_backend_cpu_set_threadpool(backend, threadpool);
    }
    threadpool
}

/// The `count` cores with the highest maximum frequency (the big and mid cores on a phone), or none when
/// the kernel does not say, in which case the pool is left unpinned. Every cpuN is looked at: the
/// numbering has holes where a core is offline.
#[cfg(target_os = "android")]
fn fastest_cores(count: usize, limit: usize) -> Vec<usize> {
    let mut cores: Vec<(u64, usize)> = std::fs::read_dir("/sys/devices/system/cpu")
        .into_iter()
        .flatten()
        .filter_map(|entry| {
            let entry = entry.ok()?;
            let index = entry.file_name().to_str()?.strip_prefix("cpu")?.parse::<usize>().ok()?;
            let path = entry.path().join("cpufreq/cpuinfo_max_freq");
            let frequency = std::fs::read_to_string(path).ok()?.trim().parse::<u64>().ok()?;
            (index < limit).then_some((frequency, index))
        })
        .collect();
    if cores.len() < count {
        return Vec::new();
    }
    cores.sort_by(|a, b| b.cmp(a));
    cores.into_iter().take(count).map(|(_, index)| index).collect()
}

/// # Safety
/// Nothing to uphold: there is no pool off Android.
#[cfg(not(target_os = "android"))]
pub unsafe fn attach_threadpool(_backend: crate::ggml_backend_t, _threads: i32) -> crate::ggml_threadpool_t {
    ptr::null_mut()
}
