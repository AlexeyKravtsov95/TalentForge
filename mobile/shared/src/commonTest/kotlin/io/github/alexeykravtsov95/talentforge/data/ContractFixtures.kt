package io.github.alexeykravtsov95.talentforge.data

/**
 * Inline contract fixtures shared by the common smoke tests. They contain every
 * schema-required field (nullable ones included), so removing a single field
 * exercises required-field semantics.
 */
internal object ContractFixtures {

    val minimalDocumentJson: String = """
    {
      "schemaVersion": 1,
      "game": "forever",
      "clientBuild": "1.60.1.70009",
      "class": { "id": 1, "token": "WARRIOR", "name": "Warrior" },
      "trees": [
        {
          "id": 1117,
          "pointBudget": { "currencyId": 3820, "maxPoints": 51 },
          "layout": { "xStep": 600, "yStep": 600 },
          "sections": [ { "index": 0, "name": null, "nodeIds": [105958] } ],
          "nodes": [
            {
              "id": 105958,
              "sectionIndex": 0,
              "position": { "x": 1020, "y": 2130, "row": 0, "column": 0 },
              "type": "single",
              "flags": 0,
              "maxRank": 3,
              "spellId": 12282,
              "name": "Improved Heroic Strike",
              "prerequisites": [ { "nodeId": 1, "kind": "sufficient" } ],
              "unlockRequirements": [ { "requiredPoints": 5, "countedNodeIds": [1, 2], "kind": "pointsSpent" } ],
              "unresolvedConditionIds": [],
              "entries": [
                {
                  "id": 130688,
                  "definitionId": 135489,
                  "spellId": 12282,
                  "name": "Improved Heroic Strike",
                  "maxRank": 3,
                  "entryType": 1,
                  "descriptionTemplate": null,
                  "effects": [
                    {
                      "index": 0,
                      "perRank": [ { "rank": 1, "value": -10 } ],
                      "perRankStatus": "resolved",
                      "basePoints": -10,
                      "unresolvedReason": null
                    }
                  ]
                }
              ]
            }
          ],
          "visualOnlyEdges": [ { "fromNodeId": 1, "toNodeId": 2 } ]
        }
      ],
      "contentHash": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
      "generatedAt": "2026-01-01T00:00:00Z",
      "provenance": { "source": "wago.tools", "tables": ["TraitNode"] }
    }
    """.trimIndent()

    val minimalBuildFileJson: String = """
    {
      "schemaVersion": 1,
      "game": "forever",
      "clientBuild": "1.60.1.70009",
      "class": "WARRIOR",
      "treeId": 1117,
      "allocations": [ { "nodeId": 105958, "entryId": 130688, "rank": 3 } ]
    }
    """.trimIndent()

    val minimalConformanceJson: String = """
    {
      "schemaVersion": 1,
      "game": "forever",
      "clientBuild": "1.60.1.70009",
      "datasets": [
        {
          "class": "WARRIOR",
          "file": "data/normalized/forever/1.60.1.70009/warrior.json",
          "contentHash": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
        }
      ],
      "scenarios": [
        {
          "id": "warrior.smoke",
          "class": "WARRIOR",
          "description": "smoke",
          "rules": ["RANK-1"],
          "synthetic": false,
          "mutations": [],
          "initial": [],
          "steps": [
            {
              "op": "spend",
              "nodeId": 105958,
              "entryId": 130688,
              "expect": {
                "ok": true,
                "reasonCodes": [],
                "issues": [],
                "spentPoints": 1,
                "remainingPoints": 50,
                "nodeRank": 1
              }
            }
          ]
        }
      ]
    }
    """.trimIndent()
}
