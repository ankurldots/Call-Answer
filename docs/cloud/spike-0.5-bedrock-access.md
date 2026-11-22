# Spike 0.5: Bedrock access for Nova 2 Sonic (ap-northeast-1)

Read-only checks, profile `ldots`. No models were invoked.

## Caller
`arn:aws:iam::517223944896:user/amplify-dev` (account 517223944896).

## Results

| Check | ap-northeast-1 | us-east-1 |
|---|---|---|
| `amazon.nova-2-sonic-v1:0` listed | yes | yes |
| Streaming (`responseStreamingSupported`) | true | true |
| Input / output modalities | SPEECH / SPEECH, TEXT | same |
| Lifecycle | ACTIVE | ACTIVE |
| Inference types | ON_DEMAND | ON_DEMAND |
| `get-foundation-model-availability` | authorization AUTHORIZED, entitlement AVAILABLE, agreement AVAILABLE, region AVAILABLE | same |
| Sonic inference profiles (`list-inference-profiles`) | none | none |

Other Sonic models listed in both regions:
- `amazon.nova-2-5-sonic` (ACTIVE, input SPEECH and TEXT, ON_DEMAND).
- `amazon.nova-sonic-v1:0` (Nova Sonic v1) is also AUTHORIZED and AVAILABLE in both regions.

IAM: `iam simulate-principal-policy` for `bedrock:InvokeModelWithBidirectionalStream` on
`arn:aws:bedrock:ap-northeast-1::foundation-model/amazon.nova-2-sonic-v1:0` returns `allowed`
(via `AdministratorAccess`). The simulator rejects mixing this action with `bedrock:InvokeModel`
in one call, so they must be simulated separately.

## Conclusions
- Model ID to use: `amazon.nova-2-sonic-v1:0`, directly in ap-northeast-1.
- No inference profile is required. It is ON_DEMAND and no Sonic profile exists.
- Access is granted. No console action is needed.
- Not verified: an actual bidirectional stream session (left to the feasibility spikes in task 1).
- Note: the availability API returns the model ID without the `:0` suffix (`amazon.nova-2-sonic-v1`). This is cosmetic.
