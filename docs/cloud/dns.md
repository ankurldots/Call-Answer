# DNS for leadingdots.com (task 0.4)

## Result

- `dig NS leadingdots.com` returns `aleena.ns.cloudflare.com` and `leland.ns.cloudflare.com` (SOA `dns.cloudflare.com`). DNS is hosted on **Cloudflare**.
- `aws route53 list-hosted-zones --profile ldots` lists only `leadingdesk.in.` (Z03788785DZI77UMOYTW) and `cashflowhq.io.` (Z0557522JH9E2SMN6DKH). There is **no Route 53 hosted zone for `leadingdots.com`**, so there is no zone id for tasks 3.3 and 14.1.
- Consequence: the SAM template must not create DNS records. In 14.1 the user creates them by hand in Cloudflare.

## Records needed in 14.1 (created by hand in Cloudflare)

1. ACM DNS-validation CNAMEs, one per certificate name, in region `ap-northeast-1`:
   - `agent.leadingdots.com`: name and value as shown in the ACM console (or `aws acm describe-certificate --profile ldots --region ap-northeast-1`).
   - `api.leadingdots.com`: same.
   - Wait until the certificate status is `ISSUED` before deploying the listeners.
2. Host name CNAMEs, after the ALB exists:
   - `agent` CNAME `<ALB DNS name>`
   - `api` CNAME `<ALB DNS name>`

## Cloudflare proxy must be OFF

Set every record above to **DNS only (grey cloud)**. Proxying would break ACM validation and put Cloudflare between Exotel and the ALB, which interferes with the direct TLS and WSS connection that the Exotel stream needs.
