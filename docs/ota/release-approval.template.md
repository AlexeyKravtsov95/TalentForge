# TalentForge OTA release approval — ${release_id}

- Release: ${release_id}
- Manifest SHA-256: ${manifest_sha256}
- Accepted gates: <OWNER: только G5 и/или G7 через запятую — то, что владелец принимает; пусто, если принимать нечего>
- Live-build evidence: <OWNER: ссылка на снимок клиента или патч-ноут, дата и что именно подтверждено>
- Source commit: ${source_commit}
- Game: ${game}
- Minimum data level: ${min_data_level}
- Created at: ${created_at}
- Release package: ${package}
- Staging verification: ${staging}
- Approved by: <OWNER: кто утверждает>
- Approval date: <OWNER: YYYY-MM-DD>

## Что утверждается

Ровно байты манифеста `${manifest_sha256}` (${manifest_byte_length} байт):
${file_count} файлов, ${total_byte_length} байт, данные коммита `${source_commit}`.
Запись не переносится на другую сборку этого релиза: другой `createdAt` или коммит дают другой
хеш, и publisher отклонит запись.

## Гейты по результатам CI

В CI гейты считаются против base `${diff_base}`, собранного из того же коммита, вместо живого
production-каталога. Окончательный прогон против live выполняют
`ota_production_release.py check` и `promote`.

| гейт | статус | деталь |
| --- | --- | --- |
${gate_rows}

FLAG в CI: ${flagged}. Принять можно только G5 и G7, и только решением владельца в строке
`Accepted gates`. STOP-гейты не принимаются никогда.

## Основание для G7 (заполняет владелец)

<OWNER: на чём основано утверждение, что `${client_build}` — сборка live-серверов.
Подтверждение версии периода беты само по себе не подтверждает версию после запуска; если
подтверждение относится только к бете, напишите это прямо.>
