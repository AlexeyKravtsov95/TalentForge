# VK Cloud templates for OTA hosting

Configuration the **owner** applies by hand (no tool in this repository creates, changes or
deletes cloud resources). Procedure: [docs/ota/production-deployment.md](../../../docs/ota/production-deployment.md).

| File | What it is |
| --- | --- |
| `production/bucket-policy.json` | Bucket policy of `talentforge-ota-production`: anonymous `s3:GetObject` on the production catalog and the content-addressed blobs only. Nothing else is public: no listing, no other key, no ACL/policy read, no write. |
| `production/aws-cli-profile.example` | The publisher's AWS CLI profile `talentforge-production` (region and endpoint only; the key pair lives in `~/.aws/credentials`, never in git). |

The bucket name, endpoint, region, profile name and public origin are the committed production
profile in `src/talent_data/ota/s3_channels.py`; `tests/unit/ota/test_ota_production_keys.py`
keeps these templates in step with it.
