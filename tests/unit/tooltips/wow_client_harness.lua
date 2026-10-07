-- A minimal stand-in for the WoW client, used only by the repository's tests to run the
-- real capture addon under a real Lua 5.1. It provides exactly the globals the addon is
-- allowed to use, records every global the addon touches, drives frames and events, and
-- serialises the saved variable the way the client writes SavedVariables files.
--
-- Returns a constructor: harness(config) -> client
--   config.version, config.build, config.locale   GetBuildInfo() / GetLocale()
--   config.tooltipInfo      false -> no C_TooltipInfo; "no-function" -> no GetTraitEntry
--   config.getTraitEntry    optional function(client, entryId, rank) -> TooltipData | nil
--   config.unknownEvent     true -> RegisterEvent("TOOLTIP_DATA_UPDATE") raises
--   config.sources          { manifest = <lua source>, addon = <lua source> }

local STANDARD = {
    "pairs", "ipairs", "type", "tostring", "tonumber", "pcall", "error", "select", "next",
    "string", "table", "math", "print", "date",
}

local function colorMixin(r, g, b)
    -- Like the client's ColorMixin: data fields plus methods stored in the same table.
    return {
        r = r, g = g, b = b, a = 1,
        GetRGB = function(self) return self.r, self.g, self.b end,
        WrapTextInColorCode = function(_, text) return text end,
    }
end

local function defaultTooltip(client, entryId, rank)
    client.nextInstance = client.nextInstance + 1
    return {
        type = 26,
        id = entryId,
        dataInstanceID = client.nextInstance,
        hasDynamicData = false,
        lines = {
            { type = 0, leftText = client.names[entryId], leftColor = colorMixin(1, 1, 1), wrapText = false },
            { type = 0, leftText = "Rank " .. rank .. "/" .. client.maxRanks[entryId],
              leftColor = colorMixin(1, 0.82, 0), wrapText = false },
            { type = 0,
              leftText = 'Increases "damage" by ' .. (rank * 5) .. '%.\r\n\r\n    |CFFFFFFFFAxe:|R naïve — 日本語\tend ',
              leftColor = colorMixin(1, 0.82, 0), wrapText = true },
        },
    }
end

return function(config)
    local client = {
        messages = {},
        calls = {},
        globalsRead = {},
        globalsWritten = {},
        nextInstance = 1000,
        names = {},
        maxRanks = {},
        scripts = {},
        events = {},
        colorMixin = colorMixin,
        defaultTooltip = defaultTooltip,
    }

    local real = {}
    for _, name in ipairs(STANDARD) do
        real[name] = _G[name]
    end
    real.print = function(...)
        local parts = {}
        for i = 1, select("#", ...) do
            parts[i] = tostring((select(i, ...)))
        end
        client.messages[#client.messages + 1] = table.concat(parts, " ")
    end
    real.date = function(format)
        assert(format == "!%Y-%m-%dT%H:%M:%SZ", "unexpected date format")
        return config.now or "2026-10-08T00:00:00Z"
    end
    real.GetBuildInfo = function()
        return config.version, config.build, "Oct  1 2026", config.interfaceVersion or 16001
    end
    real.GetLocale = function()
        return config.locale
    end
    real.UnitClass = function(unit)
        assert(unit == "player")
        return "Mage", "MAGE", 8
    end
    real.UnitLevel = function(unit)
        assert(unit == "player")
        return 60
    end
    real.SlashCmdList = {}
    real.CreateFrame = function(kind)
        assert(kind == "Frame")
        return {
            SetScript = function(_, name, handler) client.scripts[name] = handler end,
            RegisterEvent = function(_, event)
                if config.unknownEvent then
                    error("Frame:RegisterEvent(): Attempt to register unknown event \"" .. event .. "\"")
                end
                client.events[event] = true
            end,
            UnregisterEvent = function(_, event) client.events[event] = nil end,
        }
    end
    if config.tooltipInfo ~= false then
        real.C_TooltipInfo = {}
        if config.tooltipInfo ~= "no-function" then
            real.C_TooltipInfo.GetTraitEntry = function(entryId, rank)
                client.calls[#client.calls + 1] = { entryId = entryId, rank = rank }
                local provider = config.getTraitEntry or defaultTooltip
                return provider(client, entryId, rank)
            end
        end
    end

    -- Every global access of the addon goes through this proxy and is recorded.
    local environment = setmetatable({}, {
        __index = function(_, name)
            client.globalsRead[name] = true
            return real[name]
        end,
        __newindex = function(_, name, value)
            client.globalsWritten[name] = true
            real[name] = value
        end,
    })

    local namespace = {}
    local function load(source, chunkName)
        local chunk = assert(loadstring(source, chunkName))
        setfenv(chunk, environment)
        chunk("TalentForgeTooltipCapture", namespace)
    end
    load(config.sources.manifest, "=GeneratedTalentManifest.lua")
    if type(namespace.manifest) == "table" and type(namespace.manifest.entries) == "table" then
        for _, entry in ipairs(namespace.manifest.entries) do
            if type(entry) == "table" and entry.entryId then
                client.names[entry.entryId] = entry.name
                client.maxRanks[entry.entryId] = entry.maxRank
            end
        end
    end
    load(config.sources.addon, "=TalentForgeTooltipCapture.lua")

    client.namespace = namespace

    function client.slash(text)
        real.SlashCmdList["TALENTFORGETOOLTIPS"](text)
    end

    function client.slashCommand()
        return real.SLASH_TALENTFORGETOOLTIPS1
    end

    function client.running()
        return client.scripts.OnUpdate ~= nil
    end

    function client.tick(elapsed)
        local handler = client.scripts.OnUpdate
        if handler then
            handler(nil, elapsed)
        end
    end

    -- Runs frames until the addon stops its OnUpdate script. Returns the frame count.
    function client.runUntilIdle(elapsed, maxFrames)
        local frames = 0
        while client.scripts.OnUpdate do
            frames = frames + 1
            assert(frames <= maxFrames, "the addon did not finish within " .. maxFrames .. " frames")
            if config.beforeFrame then
                config.beforeFrame(client, frames)
            end
            client.scripts.OnUpdate(nil, elapsed)
        end
        return frames
    end

    function client.fire(event, ...)
        if client.events[event] and client.scripts.OnEvent then
            client.scripts.OnEvent(nil, event, ...)
        end
    end

    function client.savedValue()
        return real.TalentForgeTooltipCaptureDB
    end

    -- SavedVariables text in the client's own layout.
    function client.savedVariables()
        local value = real.TalentForgeTooltipCaptureDB
        if value == nil then
            return "\nTalentForgeTooltipCaptureDB = nil\n"
        end
        local out = { "\nTalentForgeTooltipCaptureDB = {\n" }
        for key, item in pairs(value) do
            if type(item) == "table" then
                out[#out + 1] = string.format("\t[%q] = {\n", key)
                for index, chunk in ipairs(item) do
                    out[#out + 1] = string.format("\t\t%q, -- [%d]\n", chunk, index)
                end
                out[#out + 1] = "\t},\n"
            else
                out[#out + 1] = string.format("\t[%q] = %s,\n", key, tostring(item))
            end
        end
        out[#out + 1] = "}\n"
        return table.concat(out)
    end

    function client.list(set)
        local names = {}
        for name in pairs(set) do
            names[#names + 1] = name
        end
        table.sort(names)
        return table.concat(names, ",")
    end

    return client
end
