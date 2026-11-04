# Analysis model attribution and provenance

The original MIT texts for Beat This! (Institute of Computational Perception,
JKU Linz) and Open-Unmix (Inria / SigSep) are included unchanged beside this
notice. `provenance.json` pins their repository revisions, Git blobs and hashes,
and fingerprints the actual local Android, TV, Native Desktop and Web ONNX files.

The Beat This! publisher explicitly releases its code and published model
weights under MIT. The UMX-HQ weight deposit, DOI `10.5281/zenodo.3370489`, also
records MIT. UMX-HQ and UMXL are different models; the separate non-commercial
restriction on UMXL weights must not be replaced by the code repository's MIT
label or transferred to a different checkpoint.

These publisher statements establish family/deposit license evidence. They do
not establish which exact checkpoint produced each local quantized ONNX file.
Local filenames and comments identify the intended family, but the checkpoint
fingerprint and complete export/quantization chain remain unresolved. The
retained Native Desktop beat model and current Android/Web final0 model have
different fingerprints and are inventoried separately. Neither model is changed
by this notice work.

Before claiming full model distribution readiness, record the original
checkpoint fingerprint, pinned conversion source and parameters, ONNX opset,
quantization/calibration details, and reproduction or documented numerical
equivalence for each exported artifact. Include required attribution in the
actual final packages. This notice is not a complete distribution-compliance
review and does not resolve unrelated font, native-source or relinking gates.
