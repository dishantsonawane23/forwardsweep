"""
Export a CLIP image encoder (default: Meta PE-Core-T) to ONNX and precompute the zero-shot prompt embeddings.

    pip install torch open_clip_torch onnx onnxruntime
    python eval/export_clip.py --model PE-Core-T-16-384 --pretrained meta --out models/

Default: Meta Perception Encoder PE-Core-T (Apache-2.0). Apple MobileCLIP / MobileCLIP2 weights
score slightly higher but are licensed for research only, so they cannot ship in the app.

Writes:
    models/clip_image.onnx          image tower only (text tower is not shipped)
    models/clip_image.int8.onnx     dynamically quantized copy (much less accurate; do not ship)
    models/clip_prompts.tsv         preprocessing config + one L2-normalised embedding per prompt

Use with the harness:
    forwardsweep eval eval/testset --clip models/clip_image.onnx models/clip_prompts.tsv
On Android, copy both files into app/src/main/assets/ (or download on first run).
"""
import argparse
from pathlib import Path

import torch

# Prompts were written for typical Indian WhatsApp libraries (Marathi/Hindi greetings,
# Reddit/Twitter memes, event posters). Each category's probability is the sum over its prompts.
PROMPTS = {
    "REAL_PHOTO": [
        "a candid photo taken with a phone camera",
        "a family photo of people sitting at home",
        "a group photo of friends",
        "a selfie of people smiling",
        "a photo of people at a wedding or celebration",
        "a travel photo of mountains, a waterfall or the sea",
        "a photo of a person standing at the beach",
        "a photo of food on a plate",
        "a photo of a room, a street or a building",
        "a photo of an object on a table",
        "a sunset photo",
    ],
    "GREETING": [
        "a good morning greeting card with flowers and text",
        "a good night greeting image with text",
        "a festival greeting card with decorative text",
        "a hindu festival wishes poster with a god and marathi text",
        "a birthday wishes banner with a person's photo and decorative text",
        "a thank you message card with flowers and hindi text",
        "a devotional image of a hindu god with text",
        "an inspirational quote on a colorful background",
    ],
    "MEME": [
        "an internet meme with caption text",
        "a funny reaction image with text on top",
        "a screenshot of a funny tweet",
        "a screenshot of a reddit post with comments",
        "a screenshot of a social media comment thread",
        "a news headline quote card with a politician",
        "a cartoon joke forwarded on whatsapp",
    ],
    "SCREENSHOT": [
        "a screenshot of a phone screen",
        "a screenshot of a chat conversation",
        "a screenshot of a payment receipt",
        "a screenshot of an app or website",
        "a screenshot of a map",
        "a photo of a printed document or form",
        "a photo of handwritten notes on paper",
        "a scanned identity card",
        "a qr code",
        # Added for PE-Core-T, which otherwise mistakes phone screenshots for memes (62% → 76%).
        "a screenshot of a whatsapp chat",
        "a screenshot of a bank or upi payment app",
        "a screenshot of an email",
        "a screenshot of a facebook or instagram feed",
        "a screenshot of a flight or train booking",
        "a screenshot of a video call",
        "a screenshot of a phone settings or form screen",
        "a screenshot with a status bar, icons and buttons",
        # Posters and ads: informational graphics, grouped with documents for now.
        "an event poster with date, time and venue",
        "an advertisement flyer with prices and phone numbers",
        "a wedding invitation card",
    ],
}


class ImageTower(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        return self.model.encode_image(x)


def main():
    import open_clip

    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="PE-Core-T-16-384")
    ap.add_argument("--pretrained", default="meta")
    ap.add_argument("--out", default="models")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    model, _, _ = open_clip.create_model_and_transforms(a.model, pretrained=a.pretrained)
    model.eval()
    tokenizer = open_clip.get_tokenizer(a.model)
    cfg = getattr(model.visual, "preprocess_cfg", {}) or {}
    size = cfg.get("size", 224)
    size = size[0] if isinstance(size, (tuple, list)) else size
    mean = cfg.get("mean", (0.48145466, 0.4578275, 0.40821073))
    std = cfg.get("std", (0.26862954, 0.26130258, 0.27577711))
    scale = float(model.logit_scale.exp().item()) if hasattr(model, "logit_scale") else 100.0

    onnx_path = out / "clip_image.onnx"
    kwargs = dict(input_names=["pixel_values"], output_names=["image_embeds"], opset_version=17)
    try:  # torch >= 2.5 defaults to the dynamo exporter, which needs onnxscript; use the classic one.
        torch.onnx.export(ImageTower(model), torch.randn(1, 3, size, size), str(onnx_path), dynamo=False, **kwargs)
    except TypeError:
        torch.onnx.export(ImageTower(model), torch.randn(1, 3, size, size), str(onnx_path), **kwargs)
    print("wrote", onnx_path)

    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        q = out / "clip_image.int8.onnx"
        quantize_dynamic(str(onnx_path), str(q), weight_type=QuantType.QUInt8)
        print("wrote", q)
    except Exception as e:  # quantization is optional
        print("skipped int8 quantization:", e)

    lines = [f"#size\t{size}", "#mean\t" + "\t".join(map(str, mean)), "#std\t" + "\t".join(map(str, std)), f"#scale\t{scale}"]
    with torch.no_grad():
        for cat, prompts in PROMPTS.items():
            emb = model.encode_text(tokenizer(prompts))
            emb = emb / emb.norm(dim=-1, keepdim=True)
            for p, e in zip(prompts, emb):
                lines.append(f"{cat}\t{p}\t" + ",".join(f"{v:.6f}" for v in e.tolist()))
    (out / "clip_prompts.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("wrote", out / "clip_prompts.tsv", f"({sum(map(len, PROMPTS.values()))} prompts)")


if __name__ == "__main__":
    main()
