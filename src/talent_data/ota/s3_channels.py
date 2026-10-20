"""The committed remote-store profiles of the two OTA channels (M5.OTA.4b, M5.OTA.PROD.1).

Each channel has exactly one bucket, and each bucket serves exactly one channel. The
profile is the reviewed, committed source of truth for where a channel lives; nothing
on the command line can point a channel at another bucket:

* ``staging`` - VK Cloud Object Storage ``ru-msk``, bucket ``talentforge-ota-staging``
  (OD-1, live since M5.OTA.4b), served at the bucket's own public host;
* ``production`` - the same provider and region, bucket ``talentforge-ota-production``
  (M5.OTA.PROD.1: prepared, not created by the tooling), served at the project-owned
  hostname ``https://ota.mythweld.com`` (``docs/ota/production-deployment.md``).

The S3 API endpoint and region are shared; the bucket, the publisher's AWS CLI profile
(so the two S3 keys never mix) and the public origin are not. Credentials are never part
of a profile - only the *name* of the AWS CLI profile that holds them.

This module needs no optional extra: the read-only production preflight imports it
without boto3.
"""

from __future__ import annotations

from dataclasses import dataclass

from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import Channel

VK_S3_ENDPOINT = "https://hb.ru-msk.vkcloud-storage.ru"
VK_REGION = "ru-msk"


@dataclass(frozen=True)
class S3ChannelProfile:
    """Where one channel's objects live - everything but credentials."""

    channel: Channel
    bucket: str
    endpoint_url: str
    region: str
    #: the AWS CLI profile holding this channel's publisher key (never another channel's)
    aws_profile: str
    #: the public HTTPS origin clients use (``<origin>/v1/...``)
    public_origin: str

    @property
    def bucket_origin(self) -> str:
        """The bucket's own public host (virtual-hosted style) on the provider's domain."""
        return f"https://{self.bucket}.{self.endpoint_url.removeprefix('https://')}"


STAGING_PROFILE = S3ChannelProfile(
    channel=Channel.STAGING,
    bucket="talentforge-ota-staging",
    endpoint_url=VK_S3_ENDPOINT,
    region=VK_REGION,
    aws_profile="talentforge-staging",
    public_origin="https://talentforge-ota-staging.hb.ru-msk.vkcloud-storage.ru",
)

PRODUCTION_PROFILE = S3ChannelProfile(
    channel=Channel.PRODUCTION,
    bucket="talentforge-ota-production",
    endpoint_url=VK_S3_ENDPOINT,
    region=VK_REGION,
    aws_profile="talentforge-production",
    public_origin="https://ota.mythweld.com",
)

PROFILES: dict[Channel, S3ChannelProfile] = {
    Channel.STAGING: STAGING_PROFILE,
    Channel.PRODUCTION: PRODUCTION_PROFILE,
}


def profile_of_bucket(bucket: str) -> S3ChannelProfile:
    """The profile whose bucket ``bucket`` is; any other bucket is refused."""
    for profile in PROFILES.values():
        if profile.bucket == bucket:
            return profile
    known = ", ".join(f"{p.bucket} ({p.channel.value})" for p in PROFILES.values())
    raise OtaPublisherError(f"unknown OTA bucket {bucket!r}: the committed buckets are {known}")


def require_profile(
    channel: Channel, *, bucket: str, endpoint_url: str, region: str
) -> S3ChannelProfile:
    """``channel``'s profile, if and only if the location is exactly that profile's.

    The staging bucket can never be opened as production (or the reverse), and no
    channel can be pointed at a bucket, endpoint or region outside its committed profile.
    """
    profile = PROFILES[channel]
    if bucket != profile.bucket:
        owner = next((p for p in PROFILES.values() if p.bucket == bucket), None)
        if owner is not None:
            raise OtaPublisherError(
                f"bucket {bucket!r} is the {owner.channel.value} bucket; refusing it for the "
                f"{channel.value} channel (the {channel.value} bucket is {profile.bucket!r})"
            )
        raise OtaPublisherError(
            f"bucket {bucket!r} is not the {channel.value} bucket {profile.bucket!r}"
        )
    if endpoint_url != profile.endpoint_url or region != profile.region:
        raise OtaPublisherError(
            f"the {channel.value} bucket lives at {profile.endpoint_url} ({profile.region}), "
            f"not {endpoint_url} ({region})"
        )
    return profile
