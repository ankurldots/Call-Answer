import json, sys, unittest
from unittest import mock

try:
    import boto3  # noqa: F401
except ImportError:  # boto3 is preinstalled in Lambda; stub it for local runs
    sys.modules["boto3"] = mock.MagicMock()

import handler


class FakePolly:
    def synthesize_speech(self, **kw):
        self.kw = kw
        class S:
            def read(self): return b"\x00\x00" * 160
        return {"AudioStream": S()}


class FakeDdb:
    def __init__(self):
        self.used = {}
        self.limit_error = False

    def update_item(self, **kw):
        if self.limit_error:
            e = Exception("limit")
            e.response = {"Error": {"Code": "ConditionalCheckFailedException"}}
            raise e
        self.kw = kw


class HandlerTest(unittest.TestCase):
    def setUp(self):
        self.polly = FakePolly()
        self.ddb = FakeDdb()
        patches = [
            mock.patch.object(handler, "_polly_client", return_value=self.polly),
            mock.patch.object(handler, "_ddb_client", return_value=self.ddb),
            mock.patch.dict("os.environ", {"USAGE_TABLE": "t", "DAILY_CHAR_LIMIT": "100"}),
        ]
        for p in patches:
            p.start(); self.addCleanup(p.stop)

    def call(self, body):
        event = {"body": body, "requestContext": {"authorizer": {"iam": {"cognitoIdentity": {"identityId": "ap-south-1:abc"}}}}}
        return handler.handler(event, None)

    def test_ok(self):
        r = self.call(json.dumps({"text": "hello", "language_code": "en-IN"}))
        self.assertEqual(r["statusCode"], 200)
        self.assertEqual(self.polly.kw["Engine"], "neural")

    def test_quota_exceeded(self):
        self.ddb.limit_error = True
        r = self.call(json.dumps({"text": "hello", "language_code": "en-IN"}))
        self.assertEqual(r["statusCode"], 429)
        self.assertFalse(hasattr(self.polly, "kw"))

    def test_single_request_over_limit(self):
        r = self.call(json.dumps({"text": "a" * 101, "language_code": "en-IN"}))
        self.assertEqual(r["statusCode"], 429)

    def test_quota_key_uses_identity(self):
        self.call(json.dumps({"text": "hello", "language_code": "en-IN"}))
        self.assertTrue(self.ddb.kw["Key"]["pk"]["S"].startswith("ap-south-1:abc#"))
        self.assertEqual(self.ddb.kw["ExpressionAttributeValues"][":max"]["N"], "95")

    def test_missing_identity_rejected(self):
        b = json.dumps({"text": "hello", "language_code": "en-IN"})
        self.assertEqual(handler.handler({"body": b}, None)["statusCode"], 401)

    def test_bad_language(self):
        self.assertEqual(self.call(json.dumps({"text": "x", "language_code": "fr-FR"}))["statusCode"], 400)

    def test_bad_voice(self):
        self.assertEqual(self.call(json.dumps({"text": "x", "language_code": "en-IN", "voice": "Joanna"}))["statusCode"], 400)

    def test_text_too_long(self):
        self.assertEqual(self.call(json.dumps({"text": "a" * 501, "language_code": "en-IN"}))["statusCode"], 400)

    def test_malformed_json(self):
        self.assertEqual(self.call("{nope")["statusCode"], 400)

    def test_body_too_large(self):
        self.assertEqual(self.call("a" * 5000)["statusCode"], 413)


if __name__ == "__main__":
    unittest.main()
