-- TalentForge tooltip capture: a read-only development utility.
--
-- Asks the exact Forever client for the rendered tooltip of every talent entry and rank
-- listed in GeneratedTalentManifest.lua and stores the raw answers, base64(JSON), in one
-- SavedVariables record for `python -m talent_data tooltips import-client`.
--
-- The only game APIs this file calls are:
--   C_TooltipInfo.GetTraitEntry(entryID, rank)   read-only tooltip data
--   GetBuildInfo(), GetLocale()                  capture identity
--   UnitClass("player"), UnitLevel("player")     diagnostics only
-- It never purchases, refunds, resets, stages or activates anything.

local _, ns = ...

local CAPTURE_FORMAT_VERSION = 1
local SAVED_FORMAT_VERSION = 1

local BATCH_SIZE = 20 -- tooltips requested per frame
local SETTLE_SECONDS = 1.0 -- pause after each pass so TOOLTIP_DATA_UPDATE can arrive
local MAX_PASSES = 10 -- a record that is still not final after this many passes fails
local CHUNK_BYTES = 12288 -- JSON bytes per base64 chunk; must be a multiple of 3
local CHUNKS_PER_FRAME = 8
local MAX_DEPTH = 8 -- deepest table nesting accepted inside one TooltipData
local PROGRESS_SECONDS = 0.5

local PREFIX = "TalentForge tooltips: "

local function say(message)
    print(PREFIX .. message)
end

-- ------------------------------------------------------------------------- JSON

local function escapeChar(c)
    return string.format("\\u%04x", string.byte(c))
end

-- Bytes >= 0x80 pass through untouched: client strings are UTF-8 and stay verbatim.
local function jsonString(s)
    return '"' .. (string.gsub(s, '[%c"\\]', escapeChar)) .. '"'
end

local function jsonNumber(n)
    if n ~= n or n == math.huge or n == -math.huge then
        error("non-finite number")
    end
    if n == math.floor(n) and n > -9007199254740992 and n < 9007199254740992 then
        return string.format("%.0f", n)
    end
    return string.format("%.17g", n)
end

local function jsonOptional(value, encode)
    if value == nil then
        return "null"
    end
    return encode(value)
end

-- Raw, lossless dump of a value the client returned. Nothing is renamed, trimmed or
-- interpreted. A table whose keys are exactly 1..n becomes a JSON array; any other table
-- becomes a JSON object with sorted keys. Function-valued fields (mixin methods) are not
-- data and are left out; anything else that cannot be represented is an error, so an
-- unexpected structure fails the record instead of being silently dropped.
-- `omitKey` (top level only) leaves one field out; used to compare two reads.
local function dumpValue(value, depth, omitKey)
    local kind = type(value)
    if kind == "string" then
        return jsonString(value)
    elseif kind == "number" then
        return jsonNumber(value)
    elseif kind == "boolean" then
        return value and "true" or "false"
    elseif kind ~= "table" then
        error("unsupported value type: " .. kind)
    end
    if depth > MAX_DEPTH then
        error("table nesting deeper than " .. MAX_DEPTH)
    end

    local length = #value
    local count = 0
    local isArray = length > 0
    for key in pairs(value) do
        count = count + 1
        if type(key) ~= "number" or key < 1 or key > length or key ~= math.floor(key) then
            isArray = false
        end
    end
    if isArray and count == length then
        local parts = {}
        for i = 1, length do
            parts[i] = dumpValue(value[i], depth + 1)
        end
        return "[" .. table.concat(parts, ",") .. "]"
    end

    local names, byName = {}, {}
    for key, item in pairs(value) do
        if type(item) ~= "function" then
            local name
            if type(key) == "string" then
                name = key
            elseif type(key) == "number" then
                name = jsonNumber(key)
            else
                error("unsupported key type: " .. type(key))
            end
            if byName[name] ~= nil then
                error("ambiguous table key: " .. name)
            end
            if not (depth == 1 and name == omitKey) then
                byName[name] = item
                names[#names + 1] = name
            end
        end
    end
    table.sort(names)
    local parts = {}
    for i = 1, #names do
        parts[i] = jsonString(names[i]) .. ":" .. dumpValue(byName[names[i]], depth + 1)
    end
    return "{" .. table.concat(parts, ",") .. "}"
end

-- ----------------------------------------------------------------------- base64

local B64 = {}
do
    local alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    for i = 1, 64 do
        B64[i - 1] = string.sub(alphabet, i, i)
    end
end

local function base64(data)
    local out, n = {}, 0
    local length = #data
    local floor = math.floor
    for i = 1, length - 2, 3 do
        local a, b, c = string.byte(data, i, i + 2)
        local v = a * 65536 + b * 256 + c
        n = n + 1
        out[n] = B64[floor(v / 262144)]
            .. B64[floor(v / 4096) % 64]
            .. B64[floor(v / 64) % 64]
            .. B64[v % 64]
    end
    local rest = length % 3
    if rest == 1 then
        local v = string.byte(data, length) * 65536
        n = n + 1
        out[n] = B64[floor(v / 262144)] .. B64[floor(v / 4096) % 64] .. "=="
    elseif rest == 2 then
        local a, b = string.byte(data, length - 1, length)
        local v = a * 65536 + b * 256
        n = n + 1
        out[n] = B64[floor(v / 262144)]
            .. B64[floor(v / 4096) % 64]
            .. B64[floor(v / 64) % 64]
            .. "="
    end
    return table.concat(out)
end

-- ------------------------------------------------------------------ self-checks

local function isPositiveInteger(value)
    return type(value) == "number" and value > 0 and value == math.floor(value)
end

local function clientBuild()
    local version, build, buildDate, interfaceVersion = GetBuildInfo()
    return tostring(version) .. "." .. tostring(build), version, build, buildDate, interfaceVersion
end

local function manifestProblems()
    local manifest = ns.manifest
    local problems = {}
    if type(manifest) ~= "table" then
        return { "GeneratedTalentManifest.lua did not load" }
    end
    if manifest.formatVersion ~= 1 then
        problems[#problems + 1] = "unsupported manifest format " .. tostring(manifest.formatVersion)
    end
    local entries = manifest.entries
    if type(entries) ~= "table" or #entries == 0 then
        problems[#problems + 1] = "the generated manifest has no talent entries"
        return problems
    end
    if #entries ~= manifest.entryCount then
        problems[#problems + 1] = "manifest entry count does not match its entries"
    end
    local seen, ranks = {}, 0
    for i = 1, #entries do
        local entry = entries[i]
        if
            type(entry) ~= "table"
            or not isPositiveInteger(entry.entryId)
            or not isPositiveInteger(entry.treeId)
            or not isPositiveInteger(entry.nodeId)
            or not isPositiveInteger(entry.spellId)
            or type(entry.classToken) ~= "string"
            or type(entry.name) ~= "string"
        then
            problems[#problems + 1] = "manifest entry #" .. i .. " is malformed"
        elseif not isPositiveInteger(entry.maxRank) then
            problems[#problems + 1] = "entry " .. entry.entryId .. " has no positive maxRank"
        elseif seen[entry.entryId] then
            problems[#problems + 1] = "duplicate entry id " .. entry.entryId
        else
            seen[entry.entryId] = true
            ranks = ranks + entry.maxRank
        end
    end
    if #problems == 0 and ranks ~= manifest.rankCount then
        problems[#problems + 1] = "manifest rank count does not match its entries"
    end
    return problems
end

local function apiProblems()
    if type(C_TooltipInfo) ~= "table" then
        return { "C_TooltipInfo is not available in this client" }
    end
    if type(C_TooltipInfo.GetTraitEntry) ~= "function" then
        return { "C_TooltipInfo.GetTraitEntry is not available in this client" }
    end
    return {}
end

local function identityProblems()
    local manifest = ns.manifest
    local problems = {}
    local build = clientBuild()
    if build ~= manifest.clientBuild then
        problems[#problems + 1] = "this client is build "
            .. build
            .. "; the capture requires exactly "
            .. manifest.clientBuild
    end
    local locale = GetLocale()
    if locale ~= manifest.locale then
        problems[#problems + 1] = "this client's locale is "
            .. tostring(locale)
            .. "; the canonical capture requires "
            .. manifest.locale
            .. " (the TalentForge dataset is English)"
    end
    return problems
end

local function captureProblems()
    local problems = manifestProblems()
    if #problems > 0 then
        return problems
    end
    for _, list in ipairs({ apiProblems(), identityProblems() }) do
        for i = 1, #list do
            problems[#problems + 1] = list[i]
        end
    end
    return problems
end

-- ---------------------------------------------------------------- capture state
--
-- Every (entry, rank) is one record. A record is final ("ok") only when the client
-- returned a tooltip with text and a later read, at least SETTLE_SECONDS after the
-- previous one, returned exactly the same data. A TOOLTIP_DATA_UPDATE for the record's
-- dataInstanceID (or one without an id) makes the record non-final again.
--
--   status   meaning
--   pending     not requested yet
--   unverified  has data; not yet confirmed by a second identical read
--   ok          final
--   nil         the client returned nil
--   empty       the client returned data without any text line
--   error       the call or the raw dump raised an error

local frame = CreateFrame("Frame")
local run = nil

local function hasText(data)
    local lines = data.lines
    if type(lines) ~= "table" then
        return false
    end
    for i = 1, #lines do
        local line = lines[i]
        if type(line) == "table" and type(line.leftText) == "string" and line.leftText ~= "" then
            return true
        end
    end
    return false
end

local function failRecord(record, status, message)
    record.status = status
    record.error = message
    record.json = nil
    record.compare = nil
end

local function readRecord(record)
    record.reads = record.reads + 1
    local called, data = pcall(C_TooltipInfo.GetTraitEntry, record.entry.entryId, record.rank)
    if not called then
        return failRecord(record, "error", tostring(data))
    end
    if data == nil then
        return failRecord(record, "nil", nil)
    end
    if type(data) ~= "table" then
        return failRecord(record, "error", "GetTraitEntry returned a " .. type(data))
    end
    local dumped, json = pcall(dumpValue, data, 1)
    if not dumped then
        return failRecord(record, "error", tostring(json))
    end
    local compare = dumpValue(data, 1, "dataInstanceID")

    local instance = data.dataInstanceID
    if instance ~= nil then
        local waiting = run.byInstance[instance]
        if not waiting then
            waiting = {}
            run.byInstance[instance] = waiting
        end
        waiting[record] = true
    end

    if not hasText(data) then
        record.status = "empty"
    elseif record.compare == compare then
        record.status = "ok"
    else
        record.status = "unverified"
    end
    record.error = nil
    record.json = json
    record.compare = compare
end

local function markDirty(record)
    if record.status == "ok" then
        record.status = "unverified"
    end
end

local function onTooltipDataUpdate(dataInstanceID)
    run.updateEvents = run.updateEvents + 1
    if dataInstanceID == nil then
        run.updateEventsWithoutId = run.updateEventsWithoutId + 1
        for i = 1, #run.records do
            markDirty(run.records[i])
        end
        return
    end
    local waiting = run.byInstance[dataInstanceID]
    if waiting then
        for record in pairs(waiting) do
            markDirty(record)
        end
    end
end

local function countFinal()
    local final = 0
    for i = 1, #run.records do
        if run.records[i].status == "ok" then
            final = final + 1
        end
    end
    return final
end

-- An update that arrives while a complete payload is being encoded invalidates it: the
-- payload is dropped and the affected records are verified again.
local function reopenIfInvalidated()
    if run.phase == "encode" and run.complete and countFinal() < #run.records then
        run.payload = nil
        run.chunks = nil
        run.phase = "settle"
        run.wait = SETTLE_SECONDS
    end
end

local function recordJson(record)
    local entry = record.entry
    return "{"
        .. '"classToken":'
        .. jsonString(entry.classToken)
        .. ',"entryId":'
        .. jsonNumber(entry.entryId)
        .. ',"error":'
        .. jsonOptional(record.error, jsonString)
        .. ',"nodeId":'
        .. jsonNumber(entry.nodeId)
        .. ',"rank":'
        .. jsonNumber(record.rank)
        .. ',"reads":'
        .. jsonNumber(record.reads)
        .. ',"spellId":'
        .. jsonNumber(entry.spellId)
        .. ',"status":'
        .. jsonString(record.status)
        .. ',"tooltip":'
        .. (record.json or "null")
        .. ',"treeId":'
        .. jsonNumber(entry.treeId)
        .. "}"
end

local function scalarOrNil(value)
    local kind = type(value)
    if kind == "string" or kind == "number" then
        return value
    end
    return nil
end

local function jsonScalar(value)
    if type(value) == "number" then
        return jsonNumber(value)
    end
    return jsonString(value)
end

local function buildPayload(complete)
    local manifest = ns.manifest
    local build, version, buildNumber, buildDate, interfaceVersion = clientBuild()
    local _, playerClassToken = UnitClass("player")
    local playerLevel = UnitLevel("player")

    local datasets = {}
    for i = 1, #manifest.datasets do
        local dataset = manifest.datasets[i]
        datasets[i] = '{"classToken":'
            .. jsonString(dataset.classToken)
            .. ',"contentHash":'
            .. jsonString(dataset.contentHash)
            .. "}"
    end
    local records = {}
    for i = 1, #run.records do
        records[i] = recordJson(run.records[i])
    end
    local diagnostics = "{"
        .. '"buildDate":'
        .. jsonOptional(scalarOrNil(buildDate), jsonScalar)
        .. ',"buildNumber":'
        .. jsonOptional(scalarOrNil(buildNumber), jsonScalar)
        .. ',"buildVersion":'
        .. jsonOptional(scalarOrNil(version), jsonScalar)
        .. ',"interfaceVersion":'
        .. jsonOptional(scalarOrNil(interfaceVersion), jsonScalar)
        .. ',"passes":'
        .. jsonNumber(run.pass)
        .. ',"playerClassToken":'
        .. jsonOptional(scalarOrNil(playerClassToken), jsonScalar)
        .. ',"playerLevel":'
        .. jsonOptional(scalarOrNil(playerLevel), jsonScalar)
        .. ',"updateEventRegistered":'
        .. (run.eventRegistered and "true" or "false")
        .. ',"updateEvents":'
        .. jsonNumber(run.updateEvents)
        .. ',"updateEventsWithoutId":'
        .. jsonNumber(run.updateEventsWithoutId)
        .. "}"

    return "{"
        .. '"captureFormatVersion":'
        .. jsonNumber(CAPTURE_FORMAT_VERSION)
        .. ',"capturedAt":'
        .. jsonString(date("!%Y-%m-%dT%H:%M:%SZ"))
        .. ',"clientBuild":'
        .. jsonString(build)
        .. ',"complete":'
        .. (complete and "true" or "false")
        .. ',"datasets":['
        .. table.concat(datasets, ",")
        .. '],"diagnostics":'
        .. diagnostics
        .. ',"expectedRecords":'
        .. jsonNumber(manifest.rankCount)
        .. ',"game":'
        .. jsonString(manifest.game)
        .. ',"locale":'
        .. jsonString(GetLocale())
        .. ',"manifestHash":'
        .. jsonString(manifest.manifestHash)
        .. ',"records":['
        .. table.concat(records, ",")
        .. "]}"
end

local function stopRun()
    frame:SetScript("OnUpdate", nil)
    frame:SetScript("OnEvent", nil)
    if run and run.eventRegistered then
        frame:UnregisterEvent("TOOLTIP_DATA_UPDATE")
    end
    run = nil
end

local function reportFailures()
    local byStatus, byClass, examples = {}, {}, {}
    for i = 1, #run.records do
        local record = run.records[i]
        if record.status ~= "ok" then
            byStatus[record.status] = (byStatus[record.status] or 0) + 1
            local token = record.entry.classToken
            byClass[token] = (byClass[token] or 0) + 1
            if #examples < 5 then
                examples[#examples + 1] = record.entry.entryId
                    .. " rank "
                    .. record.rank
                    .. " ("
                    .. record.status
                    .. (record.error and (": " .. record.error) or "")
                    .. ")"
            end
        end
    end
    local function summary(counts)
        local names = {}
        for name in pairs(counts) do
            names[#names + 1] = name
        end
        table.sort(names)
        for i = 1, #names do
            names[i] = names[i] .. " " .. counts[names[i]]
        end
        return table.concat(names, ", ")
    end
    say("not final by status: " .. summary(byStatus))
    say("not final by class: " .. summary(byClass))
    say("first: " .. table.concat(examples, "; "))
end

local function finishRun()
    local complete = run.complete
    TalentForgeTooltipCaptureDB = {
        formatVersion = SAVED_FORMAT_VERSION,
        payloadBytes = #run.payload,
        payloadBase64 = run.chunks,
    }
    if complete then
        say(#run.records .. " / " .. #run.records)
        say("TalentForge tooltip capture complete.")
        say("Reload or exit the game to flush SavedVariables.")
    else
        say("capture INCOMPLETE: " .. countFinal() .. " / " .. #run.records .. " tooltips final.")
        reportFailures()
        say("The stored record is marked incomplete and will be rejected by the importer.")
        say("Nothing was changed. Run /tftooltips capture to start again.")
    end
    stopRun()
end

local function beginEncode(complete)
    -- The invariant, not the fact that the queue was traversed, decides completeness.
    complete = complete
        and #run.records == ns.manifest.rankCount
        and countFinal() == #run.records
    run.complete = complete
    run.payload = buildPayload(complete)
    run.chunks = {}
    run.position = 1
    run.phase = "encode"
end

local function onUpdate(_, elapsed)
    if run.phase == "request" then
        local handled = 0
        while handled < BATCH_SIZE and run.cursor <= #run.queue do
            readRecord(run.queue[run.cursor])
            run.cursor = run.cursor + 1
            handled = handled + 1
        end
        run.sinceProgress = run.sinceProgress + elapsed
        if run.sinceProgress >= PROGRESS_SECONDS then
            run.sinceProgress = 0
            if run.pass == 1 then
                say("requesting " .. (run.cursor - 1) .. " / " .. #run.records)
            else
                say(countFinal() .. " / " .. #run.records)
            end
        end
        if run.cursor > #run.queue then
            run.phase = "settle"
            run.wait = SETTLE_SECONDS
        end
    elseif run.phase == "settle" then
        run.wait = run.wait - elapsed
        if run.wait <= 0 then
            local queue = {}
            for i = 1, #run.records do
                if run.records[i].status ~= "ok" then
                    queue[#queue + 1] = run.records[i]
                end
            end
            if #queue == 0 then
                beginEncode(true)
            elseif run.pass >= MAX_PASSES then
                beginEncode(false)
            else
                run.pass = run.pass + 1
                run.queue = queue
                run.cursor = 1
                run.phase = "request"
            end
        end
    elseif run.phase == "encode" then
        local handled = 0
        while handled < CHUNKS_PER_FRAME and run.position <= #run.payload do
            run.chunks[#run.chunks + 1] =
                base64(string.sub(run.payload, run.position, run.position + CHUNK_BYTES - 1))
            run.position = run.position + CHUNK_BYTES
            handled = handled + 1
        end
        if run.position > #run.payload then
            finishRun()
        end
    end
end

local function startCapture()
    if run then
        say("a capture is already running.")
        return
    end
    local problems = captureProblems()
    if #problems > 0 then
        say("capture NOT started:")
        for i = 1, #problems do
            say("  " .. problems[i])
        end
        return
    end

    local records = {}
    local entries = ns.manifest.entries
    for i = 1, #entries do
        for rank = 1, entries[i].maxRank do
            records[#records + 1] = { entry = entries[i], rank = rank, status = "pending", reads = 0 }
        end
    end
    run = {
        records = records,
        queue = records,
        cursor = 1,
        pass = 1,
        phase = "request",
        byInstance = {},
        updateEvents = 0,
        updateEventsWithoutId = 0,
        sinceProgress = 0,
    }
    -- Until the run finishes, the saved record says "no payload": an interrupted capture
    -- can never be mistaken for a complete one. Starting again is always safe.
    TalentForgeTooltipCaptureDB = { formatVersion = SAVED_FORMAT_VERSION }

    run.eventRegistered = pcall(frame.RegisterEvent, frame, "TOOLTIP_DATA_UPDATE")
    frame:SetScript("OnEvent", function(_, event, dataInstanceID)
        if run and event == "TOOLTIP_DATA_UPDATE" then
            onTooltipDataUpdate(dataInstanceID)
            reopenIfInvalidated()
        end
    end)
    frame:SetScript("OnUpdate", onUpdate)
    say("capturing " .. #records .. " tooltips of " .. #entries .. " talent entries (read-only)...")
end

-- ----------------------------------------------------------------------- commands

local function showStatus()
    local manifest = ns.manifest
    local build, _, _, _, interfaceVersion = clientBuild()
    say("client build " .. build .. ", interface " .. tostring(interfaceVersion))
    say("locale " .. tostring(GetLocale()))
    if type(manifest) == "table" then
        say(
            "manifest: "
                .. tostring(manifest.game)
                .. " "
                .. tostring(manifest.clientBuild)
                .. " "
                .. tostring(manifest.locale)
                .. ", "
                .. tostring(manifest.entryCount)
                .. " entries, "
                .. tostring(manifest.rankCount)
                .. " tooltips"
        )
        say("manifest hash " .. tostring(manifest.manifestHash))
    end
    local saved = TalentForgeTooltipCaptureDB
    if run then
        say("capture running: pass " .. run.pass .. ", " .. countFinal() .. " / " .. #run.records)
    elseif type(saved) == "table" and saved.payloadBase64 then
        say("stored record: " .. tostring(saved.payloadBytes) .. " bytes (the importer validates it)")
    elseif type(saved) == "table" then
        say("stored record: an unfinished capture (no payload)")
    else
        say("stored record: none")
    end
    local problems = captureProblems()
    if #problems == 0 then
        say("ready: /tftooltips capture")
    else
        say("NOT ready:")
        for i = 1, #problems do
            say("  " .. problems[i])
        end
    end
end

local function clearCapture()
    if run then
        stopRun()
        say("running capture stopped.")
    end
    TalentForgeTooltipCaptureDB = nil
    say("stored record cleared.")
end

local function visible(text)
    -- Show markup instead of letting the chat frame interpret it.
    return (string.gsub(tostring(text), "|", "||"))
end

local function probe(arguments)
    local entryText, rankText = string.match(arguments, "^(%d+)%s+(%d+)$")
    if not entryText then
        say("usage: /tftooltips probe <entryId> <rank>")
        return
    end
    local problems = apiProblems()
    if #problems > 0 then
        say(problems[1])
        return
    end
    local entryId, rank = tonumber(entryText), tonumber(rankText)
    local called, data = pcall(C_TooltipInfo.GetTraitEntry, entryId, rank)
    say("probe entry " .. entryId .. " rank " .. rank .. " on build " .. clientBuild())
    if not called then
        say("  error: " .. visible(data))
        return
    end
    if type(data) ~= "table" then
        say("  returned " .. tostring(data))
        return
    end
    say(
        "  type "
            .. tostring(data.type)
            .. ", id "
            .. tostring(data.id)
            .. ", dataInstanceID "
            .. tostring(data.dataInstanceID)
            .. ", hasDynamicData "
            .. tostring(data.hasDynamicData)
    )
    local lines = type(data.lines) == "table" and data.lines or {}
    say("  lines " .. #lines)
    for i = 1, #lines do
        local line = lines[i]
        if type(line) == "table" then
            local text = "  [" .. i .. "] type " .. tostring(line.type) .. ": " .. visible(line.leftText)
            if line.rightText ~= nil then
                text = text .. "  ||  " .. visible(line.rightText)
            end
            say(text)
        end
    end
end

SLASH_TALENTFORGETOOLTIPS1 = "/tftooltips"
SlashCmdList["TALENTFORGETOOLTIPS"] = function(message)
    local command, arguments = string.match(message or "", "^%s*(%S*)%s*(.-)%s*$")
    command = string.lower(command or "")
    if command == "status" then
        showStatus()
    elseif command == "capture" then
        startCapture()
    elseif command == "clear" then
        clearCapture()
    elseif command == "probe" then
        probe(arguments)
    else
        say("commands: status | capture | clear | probe <entryId> <rank>")
    end
end

-- Exposed for the repository's offline tests only.
ns.internal = { jsonString = jsonString, jsonNumber = jsonNumber, dumpValue = dumpValue, base64 = base64 }
