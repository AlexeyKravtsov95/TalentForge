# RuStore API TLS trust

`rustore-ca.pem` contains the public Russian Trusted Root CA and Russian Trusted
Sub CA distributed at the URLs in [RuStore's server preparation guide](https://www.rustore.ru/help/developers/monetization/payment-callback/Preparing-the-server-for-RuStore%20API):

- <https://gu-st.ru/content/lending/russian_trusted_root_ca_pem.crt>
- <https://gu-st.ru/content/lending/russian_trusted_sub_ca_pem.crt>

Retrieved over verified HTTPS on 2026-11-03. Certificate SHA-256 fingerprints:

| Certificate | SHA-256 (DER) | Expires (UTC) |
|---|---|---|
| Root | `d26d2d0231b7c39f92cc738512ba54103519e4405d68b5bd703e9788ca8ecf31` | 2032-02-27 21:04:15 |
| Intermediate | `bbbde2103e790b999ec62bd03cf625a5a2e7c316e10afe6a490eedead8b3fd9b` | 2027-03-06 11:25:19 |

Delivery loads this committed bundle in addition to normal platform trust only
for `https://public-api-m.rustore.ru`. GitHub, Huawei and other hosts retain their
normal trust. Neither system trust nor the mobile app is changed. Hostname and
certificate verification stay enabled; there is no fallback to an unverified
connection or to the legacy RuStore host. Updates require reviewing the official
source and fingerprints, then rerunning the scoped-trust regression test and a
verified TLS probe. Never put private keys or credentials here.
