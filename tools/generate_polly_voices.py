#!/usr/bin/env python3
"""Generate mono PCM16 16 kHz WAV files with Amazon Polly (voice Kajal, neural engine).

Usage:
  tools/generate_polly_voices.py SENTENCE_FILE OUT_DIR --language-code hi-IN --prefix hi
  tools/generate_polly_voices.py tools/sentences/en.txt spike/src/main/assets/s3 --language-code en-IN --prefix en

SENTENCE_FILE has one sentence per line (UTF-8); blank lines and lines starting with # are skipped.
Output files are PREFIX_01.wav, PREFIX_02.wav, ...

Credentials are never read or stored here. The AWS CLI resolves them from the named profile
(--profile, default: $AWS_PROFILE or "ldots") or the usual environment. Stops on the first AWS error.
"""
import argparse
import os
import subprocess
import sys
import tempfile
import wave


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("sentence_file")
    p.add_argument("out_dir")
    p.add_argument("--language-code", required=True, help="hi-IN or en-IN")
    p.add_argument("--prefix", required=True)
    p.add_argument("--profile", default=os.environ.get("AWS_PROFILE", "ldots"))
    p.add_argument("--region", default="ap-south-1")
    p.add_argument("--voice", default="Kajal")
    p.add_argument("--sample-rate", type=int, default=16000)
    a = p.parse_args()

    with open(a.sentence_file, encoding="utf-8") as f:
        sentences = [s.strip() for s in f if s.strip() and not s.lstrip().startswith("#")]
    os.makedirs(a.out_dir, exist_ok=True)

    for i, text in enumerate(sentences, 1):
        out = os.path.join(a.out_dir, f"{a.prefix}_{i:02d}.wav")
        with tempfile.NamedTemporaryFile(suffix=".pcm", delete=False) as tmp:
            raw_path = tmp.name
        try:
            cmd = [
                "aws", "polly", "synthesize-speech",
                "--profile", a.profile, "--region", a.region,
                "--engine", "neural", "--voice-id", a.voice,
                "--language-code", a.language_code,
                "--output-format", "pcm", "--sample-rate", str(a.sample_rate),
                "--text", text, raw_path,
            ]
            r = subprocess.run(cmd, capture_output=True, text=True)
            if r.returncode != 0:
                sys.stderr.write(f"aws polly failed for {out}:\n{r.stderr}\n")
                return 1
            with open(raw_path, "rb") as f:
                pcm = f.read()
        finally:
            os.unlink(raw_path)
        with wave.open(out, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(a.sample_rate)
            w.writeframes(pcm)
        print(f"{out}  {len(pcm) / 2 / a.sample_rate:.2f}s  {text}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
