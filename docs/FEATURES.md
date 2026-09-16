# Feature scope

## In this repository

The full feature core inherited from upstream `v1.7.4-fix`: chat, account/privacy, appearance,
debugging, engineering, Agent, backup, notification, process management, feature flags, custom
greeting, custom assistant avatar, whale animation, and the compatibility layer.

## Added by this fork

The **Local API** is implemented here as buildable sources, rather than shipped as the closed,
server-keyed payload used by the upstream Closed edition. See
[LOCAL-API.md](LOCAL-API.md) for the endpoint list, configuration and known gaps, and
[BUILDING-WINDOWS.md](BUILDING-WINDOWS.md) for the additional host-build support.

## Not in this repository

* The upstream Closed edition's encrypted payload and its activation service.
* Any public-tunnel provisioning that depends on the upstream author's servers.
