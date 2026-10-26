# ONNX Genre Classifier (GTZAN)

## Model
- **File:** `assets/ml/model_quantized.onnx` (~23 MB)
- **Source:** [onnx-community/Musical-genres-Classification-Hubert-V1-ONNX](https://huggingface.co/onnx-community/Musical-genres-Classification-Hubert-V1-ONNX)
- **Original:** [SeyedAli/Musical-genres-Classification-Hubert-V1](https://huggingface.co/SeyedAli/Musical-genres-Classification-Hubert-V1) (Apache-2.0)
- **SHA256:** f26299dd32a5b77fe343e525f8369dae2b18600e1cdf80bb33a4a9efc661af98
- **Runtime:** `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`

## Labels (only these)
blues, classical, country, disco, hiphop, jazz, metal, pop, reggae, rock

**Not** claimed: Deutschrap, Trap, Drill, Emotional Rap, Hardtekk as separate ML classes.

## Input
16 kHz mono float, mean-variance normalized, max 30 s segment.

## Priority
User correction > file tag > ML prediction > heuristics
