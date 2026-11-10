use crate::model::Model;
use crate::runtime::{CpuRuntime, Graph};
use crate::{sys, Error, Result};

#[derive(Debug, Clone, PartialEq)]
pub struct Token {
    pub id: u32,
    pub frame: usize,
    pub duration_frames: usize,
}

type Tensor = *mut sys::ggml_tensor;

/// The prediction network: one step of the LSTM stack over the last emitted token, projected for the joint.
/// Its output only changes when a token is emitted, so the decode loop runs it then and not once per frame.
struct PredictorGraph {
    graph: Graph,
    embedding: Tensor,
    prev_h: Vec<Tensor>,
    prev_c: Vec<Tensor>,
    next_h: Vec<Tensor>,
    next_c: Vec<Tensor>,
    projected: Tensor,
}

/// The joint network for one frame: both projections already applied, so it is the ReLU and the output layer.
struct JointGraph {
    graph: Graph,
    encoder: Tensor,
    predictor: Tensor,
    logits: Tensor,
}

/// The joint's encoder-side projection for every frame at once, instead of one matrix-vector product per step.
struct EncoderProjection {
    graph: Graph,
    input: Tensor,
    output: Tensor,
}

struct EmbeddingGraph {
    graph: Graph,
    id: Tensor,
    output: Tensor,
}

impl EmbeddingGraph {
    unsafe fn new(model: &Model) -> Result<Self> {
        let mut graph = Graph::new()?;
        let id = sys::ggml_new_tensor_1d(graph.ctx, sys::ggml_type_GGML_TYPE_F32, 1);
        let id_i32 = sys::ggml_cast(graph.ctx, id, sys::ggml_type_GGML_TYPE_I32);
        let output = sys::ggml_get_rows(graph.ctx, model.cpu_tensor("pred.embed.weight").unwrap(), id_i32);
        graph.output(output);
        Ok(Self { graph, id, output })
    }

    unsafe fn lookup(&mut self, runtime: &CpuRuntime, token: u32, hidden: usize) -> Result<Vec<f32>> {
        runtime.execute(&mut self.graph, &[(self.id, &[token as f32])])?;
        let mut result = vec![0.0f32; hidden];
        sys::ggml_backend_tensor_get(self.output, result.as_mut_ptr().cast(), 0, result.len() * 4);
        Ok(result)
    }
}

impl PredictorGraph {
    unsafe fn new(model: &Model) -> Result<Self> {
        let mut graph = Graph::new()?;
        let hidden = model.info().predictor_hidden as i64;
        let layers = model.info().predictor_layers as usize;
        let embedding = sys::ggml_new_tensor_1d(graph.ctx, sys::ggml_type_GGML_TYPE_F32, hidden);
        let mut prev_h = Vec::new();
        let mut prev_c = Vec::new();
        let mut next_h = Vec::new();
        let mut next_c = Vec::new();
        let mut input = embedding;
        for layer in 0..layers {
            let h = sys::ggml_new_tensor_1d(graph.ctx, sys::ggml_type_GGML_TYPE_F32, hidden);
            let c = sys::ggml_new_tensor_1d(graph.ctx, sys::ggml_type_GGML_TYPE_F32, hidden);
            prev_h.push(h);
            prev_c.push(c);
            let wx = model.decode_tensor(&format!("pred.lstm.{layer}.Wx")).unwrap();
            let wh = model.decode_tensor(&format!("pred.lstm.{layer}.Wh")).unwrap();
            let bias = model.decode_tensor(&format!("pred.lstm.{layer}.bias"));
            let mut gates = sys::ggml_add(
                graph.ctx,
                sys::ggml_mul_mat(graph.ctx, wx, input),
                sys::ggml_mul_mat(graph.ctx, wh, h),
            );
            if let Some(bias) = bias {
                gates = sys::ggml_add(graph.ctx, gates, bias);
            } else {
                let b_ih = model.decode_tensor(&format!("decoder.lstm.{layer}.b_ih")).unwrap();
                let b_hh = model.decode_tensor(&format!("decoder.lstm.{layer}.b_hh")).unwrap();
                gates = sys::ggml_add(graph.ctx, gates, sys::ggml_add(graph.ctx, b_ih, b_hh));
            }
            let part = |index: usize| {
                sys::ggml_view_1d(
                    graph.ctx,
                    gates,
                    hidden,
                    index * hidden as usize * sys::ggml_element_size(gates),
                )
            };
            let i = sys::ggml_sigmoid(graph.ctx, part(0));
            let f = sys::ggml_sigmoid(graph.ctx, part(1));
            let g = sys::ggml_tanh(graph.ctx, part(2));
            let o = sys::ggml_sigmoid(graph.ctx, part(3));
            let nc = sys::ggml_add(graph.ctx, sys::ggml_mul(graph.ctx, f, c), sys::ggml_mul(graph.ctx, i, g));
            let nh = sys::ggml_mul(graph.ctx, o, sys::ggml_tanh(graph.ctx, nc));
            graph.output(nh);
            graph.output(nc);
            next_h.push(nh);
            next_c.push(nc);
            input = nh;
        }
        let projected = sys::ggml_add(
            graph.ctx,
            sys::ggml_mul_mat(graph.ctx, model.decode_tensor("joint.pred.weight").unwrap(), input),
            model.decode_tensor("joint.pred.bias").unwrap(),
        );
        graph.output(projected);
        Ok(Self {
            graph,
            embedding,
            prev_h,
            prev_c,
            next_h,
            next_c,
            projected,
        })
    }
}

impl JointGraph {
    unsafe fn new(model: &Model) -> Result<Self> {
        let mut graph = Graph::new()?;
        let dimension = (*model.decode_tensor("joint.enc.weight").unwrap()).ne[1];
        let encoder = sys::ggml_new_tensor_1d(graph.ctx, sys::ggml_type_GGML_TYPE_F32, dimension);
        let predictor = sys::ggml_new_tensor_1d(graph.ctx, sys::ggml_type_GGML_TYPE_F32, dimension);
        let joined = sys::ggml_relu(graph.ctx, sys::ggml_add(graph.ctx, encoder, predictor));
        let logits = sys::ggml_add(
            graph.ctx,
            sys::ggml_mul_mat(graph.ctx, model.decode_tensor("joint.out.weight").unwrap(), joined),
            model.decode_tensor("joint.out.bias").unwrap(),
        );
        graph.output(logits);
        Ok(Self {
            graph,
            encoder,
            predictor,
            logits,
        })
    }
}

impl EncoderProjection {
    unsafe fn new(model: &Model, frames: usize) -> Result<Self> {
        let mut graph = Graph::new()?;
        let input = sys::ggml_new_tensor_2d(
            graph.ctx,
            sys::ggml_type_GGML_TYPE_F32,
            model.info().encoder_dimension as i64,
            frames as i64,
        );
        let output = sys::ggml_add(
            graph.ctx,
            sys::ggml_mul_mat(graph.ctx, model.decode_tensor("joint.enc.weight").unwrap(), input),
            model.decode_tensor("joint.enc.bias").unwrap(),
        );
        graph.output(output);
        Ok(Self { graph, input, output })
    }

    /// The projected frames, `joint_dimension` floats each, back to back.
    unsafe fn run(&mut self, runtime: &CpuRuntime, encoded: &[f32]) -> Result<Vec<f32>> {
        runtime.execute(&mut self.graph, &[(self.input, encoded)])?;
        let count = ((*self.output).ne[0] * (*self.output).ne[1]) as usize;
        let mut projected = vec![0.0f32; count];
        sys::ggml_backend_tensor_get(self.output, projected.as_mut_ptr().cast(), 0, count * 4);
        Ok(projected)
    }
}

impl Model {
    pub fn decode(&self, encoded: &[f32], frames: usize) -> Result<Vec<Token>> {
        let dimension = self.info().encoder_dimension as usize;
        if encoded.len() != frames * dimension {
            return Err(Error::Ggml("encoder output shape"));
        }
        if frames == 0 {
            return Ok(Vec::new());
        }
        unsafe {
            let runtime = CpuRuntime::for_decoder()?;
            let mut predictor = PredictorGraph::new(self)?;
            let mut joint = JointGraph::new(self)?;
            let mut embedding_graph = EmbeddingGraph::new(self)?;
            let hidden = self.info().predictor_hidden as usize;
            let layers = self.info().predictor_layers as usize;
            let encoder_projection = EncoderProjection::new(self, frames)?.run(&runtime, encoded)?;
            let joint_dimension = encoder_projection.len() / frames.max(1);
            let mut h = vec![vec![0.0f32; hidden]; layers];
            let mut c = vec![vec![0.0f32; hidden]; layers];
            let mut next_h = vec![vec![0.0f32; hidden]; layers];
            let mut next_c = vec![vec![0.0f32; hidden]; layers];
            let mut projected = vec![0.0f32; joint_dimension];
            // The predictor output is stale until it has run on the current embedding and state, and goes
            // stale again each time a token is emitted. A blank leaves the embedding and state alone.
            let mut stale = true;
            // NeMo TDT uses the blank embedding as the start-of-sequence
            // predictor input (not an all-zero pseudo-token).
            let mut embedding = embedding_graph.lookup(&runtime, self.tokenizer().blank_id(), hidden)?;
            let mut output = Vec::new();
            let mut frame = 0;
            let mut symbols = 0;
            let mut iterations = 0;
            while frame < frames && iterations < frames * 16 + 1024 {
                iterations += 1;
                if stale {
                    let mut inputs = vec![(predictor.embedding, embedding.as_slice())];
                    for layer in 0..layers {
                        inputs.push((predictor.prev_h[layer], h[layer].as_slice()));
                        inputs.push((predictor.prev_c[layer], c[layer].as_slice()));
                    }
                    runtime.execute(&mut predictor.graph, &inputs)?;
                    sys::ggml_backend_tensor_get(predictor.projected, projected.as_mut_ptr().cast(), 0, projected.len() * 4);
                    for layer in 0..layers {
                        sys::ggml_backend_tensor_get(predictor.next_h[layer], next_h[layer].as_mut_ptr().cast(), 0, hidden * 4);
                        sys::ggml_backend_tensor_get(predictor.next_c[layer], next_c[layer].as_mut_ptr().cast(), 0, hidden * 4);
                    }
                    stale = false;
                }
                let enc = &encoder_projection[frame * joint_dimension..(frame + 1) * joint_dimension];
                runtime.execute(
                    &mut joint.graph,
                    &[(joint.encoder, enc), (joint.predictor, projected.as_slice())],
                )?;
                let token_classes = self.info().vocabulary_size as usize;
                let mut logits = vec![0.0f32; token_classes + self.info().joint_extra_outputs as usize];
                sys::ggml_backend_tensor_get(joint.logits, logits.as_mut_ptr().cast(), 0, logits.len() * 4);
                let token = logits[..token_classes]
                    .iter()
                    .enumerate()
                    .max_by(|a, b| a.1.total_cmp(b.1))
                    .map(|x| x.0)
                    .unwrap();
                let duration_index = logits[token_classes..]
                    .iter()
                    .enumerate()
                    .max_by(|a, b| a.1.total_cmp(b.1))
                    .map(|x| x.0)
                    .unwrap_or(0);
                let duration = self.info().tdt_durations[duration_index].max(0) as usize;
                let is_blank = token as u32 == self.tokenizer().blank_id();
                if iterations <= 3 {
                    let token_logit = logits[token];
                    let blank_logit = logits[self.tokenizer().blank_id() as usize];
                    eprintln!("[parakeet][decoder] iter={iterations} frame={frame} token={token} token_logit={token_logit:.6} blank={} blank_logit={blank_logit:.6} duration={duration}", self.tokenizer().blank_id());
                }
                if !is_blank {
                    for layer in 0..layers {
                        h[layer].copy_from_slice(&next_h[layer]);
                        c[layer].copy_from_slice(&next_c[layer]);
                    }
                    output.push(Token {
                        id: token as u32,
                        frame,
                        duration_frames: duration,
                    });
                    embedding = embedding_graph.lookup(&runtime, token as u32, hidden)?;
                    stale = true;
                }
                frame += duration;
                symbols += 1;
                if duration != 0 {
                    symbols = 0;
                } else if self.info().tdt_max_symbols > 0 && symbols >= self.info().tdt_max_symbols as usize {
                    frame += 1;
                    symbols = 0;
                } else if is_blank && self.info().tdt_max_symbols > 0 {
                    iterations += self.info().tdt_max_symbols as usize - symbols;
                    frame += 1;
                    symbols = 0;
                }
            }
            if iterations >= frames * 16 + 1024 {
                return Err(Error::Ggml("RNN-T iteration limit"));
            }
            Ok(output)
        }
    }
}
