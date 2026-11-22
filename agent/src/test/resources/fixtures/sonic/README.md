# Sonic fixtures (spike C-5)

Raw Nova 2 Sonic events from `amazon.nova-2-sonic-v1:0` in ap-northeast-1, recorded by the `:spike-cloud` C-5 driver
(`./gradlew :spike-cloud:c5 -Pscenario=basic`). `in-*` are events the client sent, `out-*` are events received.
Base64 audio is shortened (`in-07-audioInput`, `out-07-audioOutput` carry a few bytes only). The `promptName`,
`contentName`, `contentId`, `completionId`, and `sessionId` values are from one real session.
Note: an `INTERRUPTED` stop reason arrives on a TEXT ASSISTANT FINAL block (`out-12`), not on the audio block.
