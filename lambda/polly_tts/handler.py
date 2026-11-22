"""Polly TTS behind API Gateway (HTTP API, AWS_IAM auth) with Cognito email/password sign-in.

API Gateway verifies the SigV4 signature before this code runs, so the handler only
validates input and calls Polly.

Request:  POST /tts, JSON body {"text": "...", "language_code": "en-IN|hi-IN", "voice": "Kajal"}
Limit:    DAILY_CHAR_LIMIT characters per Cognito identity per UTC day, else 429.
Response: 200 {"audio_base64": "<mono PCM16 16 kHz WAV>", "sample_rate": 16000, "content_type": "audio/wav"}
"""
import base64
import io
import json
import logging
import os
import time
import wave

import boto3

log = logging.getLogger()
log.setLevel(logging.INFO)

MAX_TEXT_CHARS = 500
MAX_BODY_BYTES = 4096
SAMPLE_RATE = 16000
ALLOWED_LANGUAGES = {"en-IN", "hi-IN"}
ALLOWED_VOICES = {"Kajal"}

_polly = None
_ddb = None


def _polly_client():
    global _polly
    if _polly is None:
        _polly = boto3.client("polly")
    return _polly


def _ddb_client():
    global _ddb
    if _ddb is None:
        _ddb = boto3.client("dynamodb")
    return _ddb


def _consume_quota(identity: str, chars: int, now: float) -> bool:
    """Atomically adds [chars] to today's per-identity counter. False when it would exceed the daily limit."""
    limit = int(os.environ.get("DAILY_CHAR_LIMIT", "10000"))
    if chars > limit:
        return False
    day = time.strftime("%Y%m%d", time.gmtime(now))
    try:
        _ddb_client().update_item(
            TableName=os.environ["USAGE_TABLE"],
            Key={"pk": {"S": f"{identity}#{day}"}},
            UpdateExpression="ADD chars :n SET expires = :e",
            ConditionExpression="attribute_not_exists(chars) OR chars <= :max",
            ExpressionAttributeValues={
                ":n": {"N": str(chars)},
                ":max": {"N": str(limit - chars)},
                ":e": {"N": str(int(now) + 2 * 86400)},
            },
        )
        return True
    except Exception as e:  # botocore ClientError; matched by code so botocore need not be imported
        if getattr(e, "response", {}).get("Error", {}).get("Code") == "ConditionalCheckFailedException":
            return False
        raise


def _resp(status: int, body: dict) -> dict:
    return {"statusCode": status, "headers": {"Content-Type": "application/json"}, "body": json.dumps(body)}


def _wav(pcm: bytes) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm)
    return buf.getvalue()


def _identity(event) -> str:
    iam = ((event.get("requestContext") or {}).get("authorizer") or {}).get("iam") or {}
    return (iam.get("cognitoIdentity") or {}).get("identityId", "unknown")


def handler(event, _context):
    raw = event.get("body") or ""
    if event.get("isBase64Encoded"):
        raw = base64.b64decode(raw).decode("utf-8", "replace")
    if len(raw.encode()) > MAX_BODY_BYTES:
        return _resp(413, {"error": "body too large"})
    try:
        req = json.loads(raw)
        text = req["text"]
        language = req["language_code"]
        voice = req.get("voice", "Kajal")
    except (ValueError, KeyError, TypeError):
        return _resp(400, {"error": "invalid body"})
    if (
        not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS
        or language not in ALLOWED_LANGUAGES or voice not in ALLOWED_VOICES
    ):
        return _resp(400, {"error": "invalid parameters"})

    identity = _identity(event)
    if identity == "unknown":
        return _resp(401, {"error": "unauthorized"})
    if not _consume_quota(identity, len(text), time.time()):
        return _resp(429, {"error": "daily limit reached"})
    log.info("tts identity=%s lang=%s chars=%d", identity, language, len(text))
    out = _polly_client().synthesize_speech(
        Text=text, TextType="text", VoiceId=voice, LanguageCode=language,
        Engine="neural", OutputFormat="pcm", SampleRate=str(SAMPLE_RATE),
    )
    pcm = out["AudioStream"].read()
    return _resp(200, {
        "audio_base64": base64.b64encode(_wav(pcm)).decode(),
        "sample_rate": SAMPLE_RATE,
        "content_type": "audio/wav",
    })
