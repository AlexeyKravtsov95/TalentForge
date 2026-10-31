# Graph Report - TalentForge  (2026-10-31)

## Corpus Check
- Large corpus: 690 files · ~779,719 words. Semantic extraction will be expensive (many Claude tokens). Consider running on a subfolder.

## Summary
- 13877 nodes · 44232 edges · 440 communities (305 shown, 135 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 4048 edges (avg confidence: 0.9)
- Token cost: AST uses no LLM/API tokens. Actual host Codex agent input/output token usage is not exposed by this tool interface; usage is unavailable, not zero.

## Scope and Integrity Notes
- Snapshot refreshed against main commit `97b981f7e7e2bd0bd200546762e4d61e72dd9727`. Source paths in shared outputs are repository-relative; local caches and interpreter/root hints are not versioned.
- Scope: source, tests, contracts, and selected documentation/design PDFs; see ../.graphifyignore and README.md. Canonical datasets, binary assets, release evidence, screenshots, and build outputs are excluded.
- Never traverse, read, or index ceremony/. Private NDA material remains excluded from both Git and graphify.
- All 69 selected semantic documents were read completely. Both design PDFs were read on every page, with key interaction/specification pages also inspected visually. Changed AGENTS and OTA documentation were refreshed for this snapshot.
- Extraction diagnostics: 5,460 raw edges reference endpoints not separately declared in extraction (the builder creates reference nodes); 130 self-loop edges; 3,154 undirected same-endpoint collapsed edges; 0 edges have missing endpoint values. See diagnostics.json. This graph does not retain every call-site multiplicity.
- AST parser warnings: partial extraction in BuildManagementUiTest.kt (line 359, 39 symbols), LicensesScreenUiTest.kt (line 39, 16 symbols), and OtaSessionTest.kt (line 1, 6 symbols). Seven code inputs yielded no symbols. These are graphify parser limitations, not compilation results.
- EXTRACTED describes a structural or documented relationship, not client-proven game semantics. EDGE-ACTIVE and EDGE-SUF retain their recorded hypothesis status. INFERRED links require source verification.
- benchmark.json compares synthetic query-context sizes against full-corpus reading; it does not measure answer accuracy or real task speed.

## Community Hubs (Navigation)
- Class Token
- Data pipeline command orchestration
- Russian overlay generation validation
- Product dataset regression validation
- Publisher acceptance gate evaluation
- Bundled Talent Dataset Repository
- DB2 normalization domain models
- Theme and language integration
- Fake Build Library Storage
- Client Version
- Production S3 publication tests
- Download Error
- Ota Publisher Error
- OTA publication preflight tests
- Local build library tests
- Build management overlay components
- sha256 ref
- Module pathlib
- Home and build library screens
- Talent Allocation Engine
- Typed DB2 spell records
- Module sys
- i OS archive verification tests
- About and license validation
- Installed release retention state
- Module json
- Manifest CLI validation tests
- i OS OTA platform adapters
- Adaptive talent inspector components
- OTA conformance fixture generation
- Talent Tree Build Screen kt
- Local build repository management
- Talent editing history tests
- Telegram notification command routing
- Talent and class artwork
- About and license screens
- Utc Instant
- Telegram API retry tests
- Ota Publisher Error
- orchestrate py
- test resolver py
- Typed OTA failure taxonomy
- Manifest CLI validation tests
- Build editor session loading
- Allocation engine behavior tests
- Ios Ota Device
- Module run
- Dataset Bundle Id
- Dataset download staging policy
- Kotlin allocation conformance replay
- test ota staging verify py
- Tooltip repository integrity tests
- Talent Tree Canvas kt
- OTA release snapshot verification
- Store Delivery CI Gate
- Dataset freshness classification tests
- Offline publisher pipeline tests
- Talent Tree Header Bar kt
- i OS archive signing verification
- Android release APK verification
- Ota Test Support kt
- ota historical replay py
- Owner production release commands
- Staged Update
- test ota release package py
- Candidate pull request tests
- Ota File Store
- tooltips pipeline py
- test ota release package py
- Localized application shell tests
- compose App build gradle kts
- Freshness issue watcher tests
- Edge swipe back navigation
- Local HTTPS staging stand
- Telegram delivery API client
- Application language resource consistency
- Build import application navigation
- OTA recovery scenario tests
- Play bundle manifest verification
- Python allocation rule evaluation
- Tooltip template grammar parsing
- Canonical build share codec
- Talent section motion tests
- Landscape section navigation tests
- Talent editing interface tests
- Localization Load Error
- Allocation Engine State Decisions
- Release APK verification tests
- Localized editor interface tests
- Python allocation conformance execution
- Store delivery workflow configuration
- Utc Instant
- ota historical replay py
- Client Version
- i OS OTA simulator harness
- visuals pipeline py
- Share codec canonicality tests
- HTTPS staging stand tests
- Talent Tree Viewport
- Russian editor copy fixtures
- Freshness Git Hub issue delivery
- Canonical mobile bundle synchronization
- Product dataset regression validation
- Notification delivery recovery tests
- Module Readiness
- Talent accessibility localized text
- OTA delivery architecture evidence
- Build action autosave tests
- Remote staging device checks
- Build State
- New build class selection
- Talent canvas section navigation
- Engine backed node presentation
- Asset Not Found Exception
- test ota prepare py
- Mobile bundle synchronization tests
- Localization Load Error
- Build library management tests
- Publisher client staging scenarios
- Talent data contract architecture
- Class selection interface tests
- Inspector sequential reading state
- Tooltip renderer failure tests
- Historical replay automation workflow
- Localization Load Error
- Staged Update
- Talent tree projection tests
- Bundled Talent Dataset Repository
- Allocation conformance scenario authoring
- Store Delivery Revision Gate Tests
- Build import confirmation tests
- Talent canvas gesture tests
- Allocation issue canonical ordering
- Bundled dataset integrity tests
- Product dataset regression validation
- OTA editor dataset leases
- Localized share import messages
- Bundled talent icon loading
- Talent inspector presentation facts
- Ota Fetcher Transport Test
- Inspector adaptive layout tests
- Tooltip Template Token Rendering
- Python purchase refund tests
- Local build repository management
- License notice generation tests
- Russian tooltip coverage evidence
- i OS OTA staging scenarios
- Talent Section Model Projection
- Home screen presentation tests
- Strict duplicate JSON detection
- Visual sidecar repository tests
- Exact dataset import coverage
- Talent Allocation Engine
- Store screenshot verification tests
- Talent entry numeric serialization
- Production OTA monitoring workflow
- Ios Ota Device
- Edge swipe back navigation
- Build Sharing Interface Tests
- Tooltips Load Error
- OTA signing and staging keys
- Talent Tree Viewport State
- Section Tab Interaction Tests
- Game Id
- Play Bundle Release Verification
- Build Library Storage
- OTA Preparation Workflow Variables
- Tablet Section Header Tests
- Talent Node Type
- i OS OTA simulator harness
- Google Play Delivery API
- App Store Metadata Tests
- Localized Class Build Titles
- Store Delivery Workflow Steps
- Sharing And Clipboard Contracts
- Talent Icon Interface Tests
- decode Talent Data Document
- i OS Project Release Validation
- App Store Test Flight Delivery
- Rank tooltip value evaluation
- sha256 ref
- Product dataset regression validation
- OTA update lifecycle coordination
- Talent Section Projection Tests
- Asset Not Found Exception
- visuals pipeline py
- Android Section Interaction Scenarios
- Freshness Tracking Issue State
- i OS Project Validator Tests
- Build Import Controller State
- HTTPS staging stand tests
- Authoritative Interface Design Rules
- Client Layout Grid Fitting
- Module json
- Localized Build Recency Text
- Independent Section Camera State
- Share Code Binary Primitives
- Exact tooltip numeric formatting
- Module Path
- Platform Privacy Policy Disclosures
- Telegram Smoke Test Workflow
- Inspector sizing visibility rules
- Talent Tree Geometry Projection
- Viewport Transforms And Hitboxes
- Adaptive Inspector Interaction Tests
- Landscape Editor Screenshot Evidence
- Talent Tablet Section Headers kt
- OTA Download Retry Tests
- test invariants py
- Local Build Application Flows
- Talent Data Contract
- Localization Load Error
- Dependency license notice generation
- Module argparse
- Freshness Issue Transition Tests
- Android Sharing Platform Adapters
- Talent Description Interface Tests
- Android Device Check Harness
- Mock HTTP Service Handlers
- visuals pipeline py
- Android Artifact Binary Fixtures
- Module sys
- Share Code Specification Tests
- Build Library Storage
- Mobile Architecture And Boundaries
- Delivery Python Setup Action
- Store Delivery Workflow Inputs
- Adaptive talent inspector components
- Editor Localization Resource Tests
- Dependency license notice generation
- Module argparse
- test ios privacy pages py
- Play Bundle Delivery Tests
- Telegram Recipient Setup Tests
- Store Delivery Permissions Gate
- Edge swipe back navigation
- i OS OTA platform adapters
- Ios Ota Device
- Notification event deduplication
- visuals pipeline py
- i OS Sharing Platform Adapter
- Store Upload Job Orchestration
- Store Delivery Preflight Jobs
- Back Navigation Reentrancy Tests
- Build Sharing Interface Tests
- Editor Allocation State Copy
- Ota Fetcher Transport Test
- Talent Canvas Gesture Tests
- Adaptive Inspector Screenshot Evidence
- Tooltips Load Error
- Durable Notification Git State
- Python Continuous Integration Workflow
- OTA Preparation Workflow Inputs
- Talent gesture arbitration rules
- Talent Node State
- Landscape Tree Layout Tests
- Publisher Client Interoperability Tests
- Build Sharing Screenshot Evidence
- Talent Data Contract
- Talent Data Contract
- Android Release APK Fixtures
- Store Delivery Revision Gate Tests
- Store Build Job Environment
- Responsive Build Row Tests
- Talent editing history tests
- Immutable Build State Ownership
- decode Talent Data Document
- i OS OTA simulator harness
- Module sys
- Store Delivery Orchestration Tests
- Play Bundle Signing Tests
- Desktop Sharing Clipboard Adapter
- OTA Publication And Release Readiness
- Localized Editor Layout Tests
- Talent gesture arbitration rules
- ready State Of
- decode Talent Data Document
- ota historical replay py
- Freshness Report Consistency Validation
- i OS Simulator Runtime Selection
- Thin Swift UI Application Host
- Integration Test Workflow
- Asset Not Found Exception
- Ota Ui Test kt
- Russian Talent Screenshot Evidence
- Section Navigation Screenshot Evidence
- Game Id
- Asset Not Found Exception
- decode Talent Data Document
- decode Talent Data Document
- Module json
- Real Allocation Structure Regressions
- OTA Crash Recovery Tests
- OTA Preparation Workflow Triggers
- OTA Preparation Artifact Settings
- Release About Screenshot Evidence
- Responsive Action Button Labels
- Specialization Label Screenshot Evidence
- Tree Gesture Recording
- Game Id
- Talent entry numeric serialization
- Strict JSON Scanner Tests
- Candidate pull request tests
- Freshness Notification Workflow
- Candidate Proposal Job Outputs
- Release Package Workflow Configuration
- Candidate Staging Client Tests
- Talent Tree
- Android Section Interaction Scenarios
- Android Device Check Harness
- Play Signing Self Tests
- Test Flight Distribution API Tests
- OTA Publication And Release Readiness
- Mobile CI Dependency Setup
- Release Package Build Inputs
- Staging Verification Dependency Setup
- Tablet Section Header Layout
- load Baseline Warrior Tree State
- Tooltips Load Error
- Game Id
- i OS OTA simulator harness
- Publisher Client Interoperability
- Scheduled Dataset Freshness Checks
- Mac Mobile CI Environment
- Mobile CI Production OTA
- Candidate Preparation Workflow Steps
- Release Package Job Steps
- Staging Workflow Triggers
- Design Colour Token Validation
- OTA Crash Recovery Tests
- Historical Patch Replay Tests
- load Baseline Warrior Tree State
- Tablet Section Header Screenshots
- load Baseline Warrior Tree State
- Canonical mobile bundle synchronization
- i OS Simulator Smoke Runner
- Release Signing Self Tests
- Freshness Check Workflow Steps
- Freshness Workflow Artifact Setup
- Release Package Environment Inputs
- Release Package Artifact Setup
- i OS Store Upload Job
- JSON Scanner Parsing States
- Dependency license notice generation
- S3 Client Adapter Interface
- ota historical replay py
- Tooltip template grammar parsing
- Mobile CI Workflow Configuration
- Build action autosave tests
- Specialization Label Screenshot Evidence
- Build Sharing Interface Tests
- load Baseline Warrior Tree State
- Immutable Build State Ownership
- OTA Performance And Retry
- Freshness Notification Environment
- Preparation Notification Job
- Staging Verification Environment Inputs
- Staging Source Revision Input
- Staging Verification Workflow Steps
- Android Store Build Job
- Talent gesture arbitration rules
- Bundled Tooltip Drift Checks
- Allocation Mutation Routing Enforcement
- Bundled Visual Drift Checks
- Visual sidecar repository tests
- Local HTTPS staging stand
- store delivery py
- Store Artifact OTA Validation
- Linux Mobile CI Job
- Mobile CI Event Triggers
- Candidate Workflow Permission Boundaries
- Class Token
- Bundled Dataset Drift Checks
- Bundled Localization Drift Checks
- i OS Delivery Signing Setup
- Store Delivery Repository Fixtures
- Freshness Check Job Outputs
- Release Revision Workflow Input
- Release Source Revision Input
- Staging Base Build Input
- Staging Candidate Build Input
- Inspector And Back Actions
- write Synced
- Talent Allocation Engine
- Android System Back Adapter
- i OS OTA CI Job
- Release Staging Evidence Input
- Staging Verification Job Configuration
- Home and build library screens
- Talent gesture arbitration rules
- Gated Storage
- i OS Catalog Fixture Vectors
- Build Import Test Assets
- OTA Integration Check Runner
- Tooltip Spell Reference Traversal
- JPEG Library License Notices
- Desktop Reduced Motion Preference
- Landscape section navigation tests
- i OS System Back Adapter
- Store Delivery Orchestration Tests
- VK Cloud Hosting Templates
- Talent Tree Design Authority
- Talent Tree Canvas Metrics
- Shared Contract Test Fixtures
- Module Path
- Release APK Validator Loading
- Talent Tree Design Authority
- Python Project Package Configuration

## God Nodes (most connected - your core abstractions)
1. `TalentDataDocument` - 251 edges
2. `ClassToken` - 242 edges
3. `ClientVersion` - 163 edges

## Surprising Connections (you probably didn't know these)
- `iosProductionRuleFailures()` --calls--> `ios`  [INFERRED]
  mobile/composeApp/build.gradle.kts → .github/workflows/store-delivery.yml
- `test_cli_check_passes_on_the_committed_files()` --calls--> `main()`  [INFERRED]
  tests/unit/localization/test_contract_and_pipeline.py → src/talent_data/cli/main.py
- `test_cli_refuses_an_unsupported_locale()` --calls--> `main()`  [INFERRED]
  tests/unit/localization/test_contract_and_pipeline.py → src/talent_data/cli/main.py

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Presentation sidecars bind to exact canonical dataset revisions** — docs_talent_visuals_visuals_sidecars, docs_talent_tooltips_talent_tooltips_v1, docs_talent_localization_talent_localization_v1 [EXTRACTED 1.00]
- **Per-build Russian output depends on independently verified text source and English rendering coverage** — docs_research_forever_ruru_localization_feasibility_verified_locale_source, docs_research_forever_1_60_1_70338_ruru_coverage_russian_text_coverage, docs_research_forever_1_60_1_70338_tooltip_coverage_rank_description_coverage [EXTRACTED 1.00]
- **Policy, About alignment and owner store declarations** — docs_legal_ota_on_readme_privacy_copy_alignment, docs_legal_ota_on_store_declarations_network_facts, docs_legal_ota_on_owner_action_required_owner_open_actions [EXTRACTED 1.00]
- **Exact dataset identity across saved builds, presentation assets, localization and OTA sessions** — docs_mobile_architecture_dataset_identity, docs_mobile_architecture_local_build_library, docs_mobile_architecture_visual_tooltip_sidecars, docs_mobile_architecture_localization, docs_mobile_architecture_ota [EXTRACTED 1.00]
- **Pure shared decisions, Compose application and thin iOS hosting** — docs_mobile_architecture_shared, docs_mobile_architecture_composeapp, docs_mobile_architecture_iosapp [EXTRACTED 1.00]
- **Staging evidence, exact-byte approval and owner publication** — docs_ota_staging_verification_pinned_candidate_verification, docs_ota_release_approval_template_exact_manifest_approval, docs_ota_publisher_runbook_gated_publication [EXTRACTED 1.00]
- **Explicit tree editing preserves state and context** — docs_design_talentforge_talenttree_b3_gesture_contract, docs_design_talentforge_talenttree_b3_selection_overlay, docs_design_talentforge_talenttree_b3_compact_inspector, docs_design_talentforge_talenttree_b3_show_navigation [INFERRED 0.95]
- **Fail-closed release trust across publisher and client** — docs_ota_adr_0001_remote_dataset_delivery_signed_hash_chain, docs_ota_ota_contract_v1_ordered_verification, docs_ota_publisher_and_security_publisher_gates, docs_ota_client_implementation_notes_strict_client_reader [INFERRED 0.95]
- **Successful release notifications require full public-byte verification before shared deduplication** — docs_superpowers_specs_2026_10_31_ota_github_publication_design_publication_outcome_verification, docs_ota_telegram_notifications_verified_publication_notifications, docs_ota_telegram_notifications_durable_delivery_deduplication [INFERRED 0.95]

## Communities (440 total, 135 thin omitted)

### Community 0 - "Class Token"
Cohesion: 0.02
Nodes (62): TalentNodeDescriptionsLoad, TalentNodeIconsLoad, toDisplayRank(), DraftCreation, DatasetAcquisition, Downloading, Unavailable, bundle() (+54 more)

### Community 1 - "Data pipeline command orchestration"
Cohesion: 0.02
Nodes (133): _build_parser(), _cmd_builds_freshness(), _cmd_builds_latest(), _cmd_builds_list(), _cmd_diff(), _cmd_fetch(), _cmd_manifest(), _cmd_normalize() (+125 more)

### Community 2 - "Russian overlay generation validation"
Cohesion: 0.02
Nodes (140): json_schemas(), _schema_text(), ClassText, EntryText, resolve_class_text(), _definition(), _describe(), _entries() (+132 more)

### Community 3 - "Product dataset regression validation"
Cohesion: 0.02
Nodes (138): diff_documents(), DiffError, _dump(), _effect_changes(), _entries_changes(), _entry_changes(), _group_by_spell(), _index_nodes() (+130 more)

### Community 4 - "Publisher acceptance gate evaluation"
Cohesion: 0.03
Nodes (106): phase_prepare(), DatasetManifest, releases_of_build(), any_stop(), ApprovalRecord, _dataset_manifest(), _fingerprint(), Gate (+98 more)

### Community 5 - "Bundled Talent Dataset Repository"
Cohesion: 0.02
Nodes (57): ComposeResourceDatasetAssetSource, TalentDescriptionsLoader, TalentIconsLoader, TalentLocalizationLoader, classAccentFor(), TalentTreeBuildScreen(), AllocationEngine, Dataset (+49 more)

### Community 6 - "DB2 normalization domain models"
Cohesion: 0.03
Nodes (120): fmt(), inventory(), main(), extract_class(), TraitDataSet, unique(), ChrClass, TraitCond (+112 more)

### Community 7 - "Theme and language integration"
Cohesion: 0.09
Nodes (12): AppLanguage, ENGLISH, RUSSIAN, TalentForgeTheme(), DesktopBackDispatcher, RecordingUriHandler, runAboutUiTest(), AppLanguageRule (+4 more)

### Community 8 - "Fake Build Library Storage"
Cohesion: 0.02
Nodes (24): App(), AppEnvironment, NewBuildScreen(), SessionDatasets, M5UiAbout1ScreenshotTest, FakeBuildLibraryStorage, FixedClock, SequentialIds (+16 more)

### Community 9 - "Client Version"
Cohesion: 0.03
Nodes (93): _cmd_replay(), _cmd_validate(), register(), _cmd_check(), _cmd_fetch(), _cmd_normalize(), _cmd_report(), _localize() (+85 more)

### Community 10 - "Production S3 publication tests"
Cohesion: 0.04
Nodes (82): key(), CatalogUpdate, resign_catalog(), ReleaseArtifact, _classify(), refuse(), S3BlobStore, moved() (+74 more)

### Community 11 - "Download Error"
Cohesion: 0.04
Nodes (66): extract_localization(), extract_tooltips(), main(), _write_table(), WagoLocalizedDb2Source, InputError, resolve_subject(), CascFileError (+58 more)

### Community 12 - "Ota Publisher Error"
Cohesion: 0.04
Nodes (76): _all_upload_blobs(), _blob_key(), _cmd_attach_signature(), read_file(), _cmd_build_release(), _cmd_gc(), _cmd_promote(), _cmd_report() (+68 more)

### Community 13 - "OTA publication preflight tests"
Cohesion: 0.05
Nodes (86): Status, TlsInfo, TlsRefused, serialize_manifest(), ProductionStatus, alter_file(), catalog_of(), check() (+78 more)

### Community 14 - "Local build library tests"
Cohesion: 0.07
Nodes (15): libraryClientBuilds(), LocalBuildLibraryCodec, LocalBuildLibraryFile, LocalBuildLibraryFormatException, LocalBuildRecordFile, Malformed, UnsupportedVersion, FixedBuildClock (+7 more)

### Community 15 - "Build management overlay components"
Cohesion: 0.03
Nodes (22): BuildManagementMetrics, BuildRowActionsSheet(), DeleteBuildDialog(), DialogButtonRow(), DialogScrim(), DialogTextButton(), RenameBuildDialog(), SheetAction() (+14 more)

### Community 16 - "sha256 ref"
Cohesion: 0.04
Nodes (50): phase_stage(), bind(), build(), ci_gates(), conditions(), _git(), main(), render_conditions() (+42 more)

### Community 17 - "Module pathlib"
Cohesion: 0.03
Nodes (49): payload_of(), SignedEnvelope, ActivationDecision, _check_catalog_consistency(), fail(), _check_layout(), fail(), _check_same_build_identity() (+41 more)

### Community 18 - "Home and build library screens"
Cohesion: 0.05
Nodes (43): Actions, AllBuildsScreen(), BuildRow(), BuildRowContent(), Hidden, ImportBuildButton(), isCompactRow(), ManagementOverlay (+35 more)

### Community 19 - "Talent Allocation Engine"
Cohesion: 0.06
Nodes (15): createClassDraft(), classArtworkResourceFor(), Names, talentSectionDisplayLabel(), TalentSpecializationNames, talentSectionTabs(), BuildCardMetadataTest, ClassArtworkTest (+7 more)

### Community 20 - "Typed DB2 spell records"
Cohesion: 0.03
Nodes (41): rows(), Curve, CurvePoint, Db2Record, _float(), GlobalString, _int(), parse_table() (+33 more)

### Community 21 - "Module sys"
Cohesion: 0.04
Nodes (40): test_key(), build_keyring(), check_signature(), key_spec(), KeyInputError, main(), public_key_of(), spki_der() (+32 more)

### Community 22 - "i OS archive verification tests"
Cohesion: 0.09
Nodes (71): app_info(), app_store_profile(), codesign_display(), distribution_tools(), failed(), keyring(), load_validator(), macho() (+63 more)

### Community 23 - "About and license validation"
Cohesion: 0.06
Nodes (31): BackupStatement, AndroidBackupDisabled, IosDeviceBackup, LegalLinks, privacyPolicyLinkOf(), privacyPolicyUrlOf(), PublishedPrivacyPolicy, Android (+23 more)

### Community 24 - "Installed release retention state"
Cohesion: 0.05
Nodes (18): Absent, DatasetCache, StateRead, Unreadable, Valid, CatalogWire, InstallAttempt, InstallAttemptCodec (+10 more)

### Community 25 - "Module json"
Cohesion: 0.04
Nodes (50): check_canonical(), read_coverage_report(), check_visuals_document(), test_a_build_s_overlays_are_its_own(), bundled_builds(), canonical(), generate(), test_every_bundled_overlay_is_a_vector_and_carries_its_python_hash() (+42 more)

### Community 26 - "Manifest CLI validation tests"
Cohesion: 0.05
Nodes (43): _casc(), _cmd_check(), _cmd_fetch(), _cmd_normalize(), _cmd_report(), _db2(), _print_report(), register() (+35 more)

### Community 27 - "i OS OTA platform adapters"
Cohesion: 0.03
Nodes (4): BoundedDataDelegate, PlatformBlobFetcher, PlatformSignatureVerifier, toByteArray()

### Community 28 - "Adaptive talent inspector components"
Cohesion: 0.06
Nodes (46): Row, TalentForgeMotion, NeutralSheetAction(), TalentBuildActionsSheet(), TalentBuildOverlayMetrics, TalentResetNotice, TalentResetSnackbar(), ChevronButton() (+38 more)

### Community 29 - "OTA conformance fixture generation"
Cohesion: 0.05
Nodes (52): accept(), _catalog_cases(), case(), catalog_payload(), signed(), catalog_context(), drifted(), dumps() (+44 more)

### Community 30 - "Talent Tree Build Screen kt"
Cohesion: 0.04
Nodes (29): BuildActionsOverlays(), BuildActionsPresenter, Failed, isMissingRecord(), Missing, PendingSaveFailed, rememberBuildActionsPresenter(), RenameProblem (+21 more)

### Community 31 - "Local build repository management"
Cohesion: 0.07
Nodes (30): BuildAutosaveCoordinator, BuildRenameOutcome, Failed, PendingSaveFailed, Renamed, SaveRequest, SaveResult, Created (+22 more)

### Community 32 - "Talent editing history tests"
Cohesion: 0.07
Nodes (4): TalentBuildEditingState, TalentBuildEditResult, TalentBuildEditingStateTest, WarriorInteractiveAllocationTest

### Community 33 - "Telegram notification command routing"
Cohesion: 0.06
Nodes (39): cmd_candidate(), cmd_freshness(), cmd_production(), _deliver(), main(), _now(), _parser(), _read_detection_reason() (+31 more)

### Community 34 - "Talent and class artwork"
Cohesion: 0.04
Nodes (8): drawDashedRoundedRectStroke(), drawPurchaseGlow(), drawShowPulse(), PadlockGlyph(), RankChip(), RankChipContent(), ringStrokeInset(), TalentNodeVisual()

### Community 35 - "About and license screens"
Cohesion: 0.06
Nodes (38): AboutBody(), AboutMeta(), AboutNavigationRow(), AboutScaffold(), AboutSection(), AboutToggle(), drawForwardChevron(), ForwardChevron() (+30 more)

### Community 36 - "Utc Instant"
Cohesion: 0.05
Nodes (10): LocalOrigin, OtaPlatformAdapterTest, TestOtaClock, ClientBuildVersion, ReleaseId, UtcInstant, OtaConformanceReplayTest, Verdict (+2 more)

### Community 37 - "Telegram API retry tests"
Cohesion: 0.07
Nodes (50): ProductionObservation, initial_freshness(), initial_production(), SendStatus, client(), expected_outcome(), fact(), fail() (+42 more)

### Community 38 - "Ota Publisher Error"
Cohesion: 0.06
Nodes (42): catalog_entry(), catalog_summary(), next_catalog(), rollback_catalog(), serialize_catalog(), blob_key(), ensure_blob(), upload_blobs() (+34 more)

### Community 39 - "orchestrate py"
Cohesion: 0.06
Nodes (37): artifact_name(), kept(), FindingKind, StepKind, StepOutcome, _author_email(), Candidate, file_changes() (+29 more)

### Community 40 - "test resolver py"
Cohesion: 0.06
Nodes (41): SpellMisc, read_visuals_document(), build_visuals_document(), display_spell_id(), IconResolution, index_spell_misc(), _required(), resolve_document() (+33 more)

### Community 41 - "Typed OTA failure taxonomy"
Cohesion: 0.06
Nodes (60): BlobHashMismatch, BlobSizeMismatch, BuildIdentityConflict, Cancelled, CatalogExpired, CatalogInconsistent, CatalogTooLarge, ChannelMismatch (+52 more)

### Community 42 - "Manifest CLI validation tests"
Cohesion: 0.06
Nodes (42): main(), source_unavailable(), ManifestClassEntry, ManifestValidation, _Model, _merge_class_fixtures(), project(), test_diff_all_classes_writes_aggregate() (+34 more)

### Community 43 - "Build editor session loading"
Cohesion: 0.07
Nodes (26): Dataset, DatasetClassResolver, EditorFailure, EditorLoad, EditorSession, Engine, Failed, Failure (+18 more)

### Community 45 - "Ios Ota Device"
Cohesion: 0.06
Nodes (37): Disabled, DisabledReason, BAD_BASE_URL, BAD_KEYRING, KEY_NOT_FOR_CHANNEL, NO_KEYS, NOT_ENABLED, PRODUCTION_NEEDS_BACKUP_KEY (+29 more)

### Community 46 - "Module run"
Cohesion: 0.07
Nodes (29): _check_active_manifest(), observe_production(), _fetch(), _PublicFailure, PublicVerification, _require(), verify_publication(), read() (+21 more)

### Community 47 - "Dataset Bundle Id"
Cohesion: 0.06
Nodes (21): BundleInventory, DatasetActivationManager, IntactRelease, RecoveryReport, Available, DatasetResolution, DatasetUnavailableReason, DownloadFailed (+13 more)

### Community 48 - "Dataset download staging policy"
Cohesion: 0.06
Nodes (33): Abort, Acquired, Counters, DatasetDownloadManager, InstallReport, Origin, BUNDLE, INSTALLED (+25 more)

### Community 49 - "Kotlin allocation conformance replay"
Cohesion: 0.07
Nodes (24): AllocationConformance, StepOutcome, ConformanceDatasetRef, ConformanceMutation, ConformanceMutationOp, ADD_PREREQUISITE, ADD_UNRESOLVED_CONDITION, SET_ENTRY_TYPE (+16 more)

### Community 50 - "test ota staging verify py"
Cohesion: 0.06
Nodes (36): exit_code(), _client(), _evidence(), _git(), _gradle(), run(), repo(), _report() (+28 more)

### Community 51 - "Tooltip repository integrity tests"
Cohesion: 0.08
Nodes (22): AssetMissing, AssetUnreadable, BuildMismatch, ClassMismatch, DatasetRevisionMismatch, DuplicateEntry, EntryIdentityMismatch, Failed (+14 more)

### Community 52 - "Talent Tree Canvas kt"
Cohesion: 0.05
Nodes (13): BackSwipeStack(), SwipePhase, Dragging, Idle, Settling, CanvasWindowFrame, centroidOf(), spanOf() (+5 more)

### Community 53 - "OTA release snapshot verification"
Cohesion: 0.08
Nodes (15): RemoteDatasetCatalog, CatalogVerifier, SeenCatalog, VerifiedCatalog, OtaDocuments, Err, Ok, OtaResult (+7 more)

### Community 54 - "Store Delivery CI Gate"
Cohesion: 0.08
Nodes (35): _asc(), check_android_release(), check_ci(), check_ios_release(), delivery_records(), _dex(), _failed_jobs(), file_sha256() (+27 more)

### Community 55 - "Dataset freshness classification tests"
Cohesion: 0.09
Nodes (37): FreshnessStatus, check(), http(), kotlin(), latest_publishable(), load_script(), payload(), payload_70374() (+29 more)

### Community 56 - "Offline publisher pipeline tests"
Cohesion: 0.13
Nodes (31): env(), Env, read_file(), _git(), neg_env(), _pending(), _rewrite_pending(), test_a_failed_blob_upload_leaves_the_live_catalog_untouched() (+23 more)

### Community 57 - "Talent Tree Header Bar kt"
Cohesion: 0.06
Nodes (20): ClassAccent, TalentForgeRadius, TalentForgeSpacing, BackChevronButton(), drawBackChevron(), drawHistoryArrow(), HistoryIconButton(), OverflowButton() (+12 more)

### Community 58 - "i OS archive signing verification"
Cohesion: 0.08
Nodes (30): _as_utc(), Check, _codesign_fields(), contains_text(), evaluate(), _evaluate_app(), evaluate_ipa(), Evaluation (+22 more)

### Community 59 - "Android release APK verification"
Cohesion: 0.07
Nodes (34): add_certificate_role_checks(), Badging, build_report(), Check, debug_certificate_sha256(), elf_load_alignments(), elf_segments(), evaluate() (+26 more)

### Community 60 - "Ota Test Support kt"
Cohesion: 0.05
Nodes (9): fetchRetrying(), FakeBlobServer, TestRelease, pinnedTrustSocketFactory(), PlatformBlobFetcher, FetchResult, HttpStatus, NetworkError (+1 more)

### Community 61 - "ota historical replay py"
Cohesion: 0.08
Nodes (29): checkout_state(), client_not_established(), _git(), _load_stand(), main(), not_executed(), _now(), _phase() (+21 more)

### Community 62 - "Owner production release commands"
Cohesion: 0.10
Nodes (24): Ambiguous, approvals_dir(), assess(), cmd_check(), cmd_prepare(), cmd_publish(), cmd_verify(), journal() (+16 more)

### Community 63 - "Staged Update"
Cohesion: 0.07
Nodes (13): OtaRuntime, RoutingDatasetAssetSource, RecordedSession, Corruption, BLOB, MANIFEST, SIGNATURE, StagedUpdate (+5 more)

### Community 64 - "test ota release package py"
Cohesion: 0.11
Nodes (24): LocalDirectoryStore, sign_der(), _change(), counted_puts(), owner(), Owner, test_a_catalog_written_by_another_publisher_while_attaching_is_ambiguous(), test_a_damaged_package_is_refused_before_anything_is_written() (+16 more)

### Community 65 - "Candidate pull request tests"
Cohesion: 0.14
Nodes (39): Outcome, main(), bash(), branch_tip(), CoverageRunner, _deletion(), _executable(), FakeGitHub (+31 more)

### Community 66 - "Ota File Store"
Cohesion: 0.08
Nodes (8): ReleaseAssetSource, PlatformOtaFileStore, PlatformOtaFileStore, PlatformSignatureVerifier, Body, OtaFileEntry, OtaFileStore, OtaIoException

### Community 67 - "tooltips pipeline py"
Cohesion: 0.07
Nodes (32): _cmd_check(), _cmd_fetch(), _cmd_normalize(), _cmd_report(), _db2(), _print_report(), register(), _render() (+24 more)

### Community 68 - "test ota release package py"
Cohesion: 0.08
Nodes (35): api_run(), build_package(), filled_record(), load_script(), staging_evidence(), write_staging_artifact(), _bind(), _build() (+27 more)

### Community 69 - "Localized application shell tests"
Cohesion: 0.13
Nodes (4): Build, ShellCopy, ShellLibraryFixtures, ShellLocalizationUiTest

### Community 70 - "compose App build gradle kts"
Cohesion: 0.07
Nodes (18): AssemblePublishableApk, BundlePublishablePlay, CheckReleaseInternetPermission, iosProductionRuleFailures(), keyringFingerprintProblems(), OtaChannelInput, otaChannelInputOf(), otaKotlinString() (+10 more)

### Community 71 - "Freshness issue watcher tests"
Cohesion: 0.11
Nodes (31): build(), document(), FakeGitHub, never_connect(), produced(), serving_fixture(), test_a_current_document_must_name_the_product_build(), test_a_failure_while_writing_is_undelivered_too() (+23 more)

### Community 72 - "Edge swipe back navigation"
Cohesion: 0.06
Nodes (11): PlatformEdgeSwipeInput(), EdgeSwipeBackDispatcher, EdgeSwipeBackHandler(), Handler, Handler, PlatformEdgeSwipeInput(), PlatformEdgeSwipeInput(), Handler (+3 more)

### Community 73 - "Local HTTPS staging stand"
Cohesion: 0.09
Nodes (20): activate(), generate_staging_key(), generate_tls(), _git(), inside_git_checkout(), main(), make_server(), _pkcs8_pem() (+12 more)

### Community 74 - "Telegram delivery API client"
Cohesion: 0.07
Nodes (19): candidate_chats(), ChatCandidate, _iso(), main(), pick(), cmd_smoke(), _description(), _description_body() (+11 more)

### Community 75 - "Application language resource consistency"
Cohesion: 0.06
Nodes (10): devicePrimaryLanguageTag(), platformPrimaryLanguageTag(), appLanguageOf(), platformPrimaryLanguageTag(), primaryLanguageTagOf(), resourceAppLanguage(), platformPrimaryLanguageTag(), AppLanguageResourceConsistencyTest (+2 more)

### Community 76 - "Build import application navigation"
Cohesion: 0.11
Nodes (17): About, AllBuilds, AppDestination, decodeDestinations(), Editor, EditorOrigin, CreatedNew, FromAllBuilds (+9 more)

### Community 77 - "OTA recovery scenario tests"
Cohesion: 0.12
Nodes (5): OtaScenarioTest, SpaceStore, catalog(), OtaTestRepo, signedCatalog()

### Community 78 - "Play bundle manifest verification"
Cohesion: 0.09
Nodes (27): _attribute_value(), build_report(), BundleManifest, BundleSignature, evaluate(), _flag(), _int_or_none(), _load_apk_checks() (+19 more)

### Community 79 - "Python allocation rule evaluation"
Cohesion: 0.08
Nodes (18): expected_issue(), _entry_of(), _invalid_entry(), _unknown_node(), AllocationIssue, sort_issues(), availability_issues(), can_purchase_next_rank() (+10 more)

### Community 80 - "Tooltip template grammar parsing"
Cohesion: 0.08
Nodes (23): value_signature(), category_of(), _closing(), Color, Conditional, DescriptionVariable, Expression, _expression_ref() (+15 more)

### Community 81 - "Canonical build share codec"
Cohesion: 0.09
Nodes (33): InvalidCode, BuildShareCode, ChecksumMismatch, CodeTooLong, Decoded, DuplicateEntry, DuplicateNode, Empty (+25 more)

### Community 82 - "Talent section motion tests"
Cohesion: 0.19
Nodes (3): talentSectionPageTag(), Session, TalentTreeMotionUiTest

### Community 85 - "Localization Load Error"
Cohesion: 0.09
Nodes (35): TalentLocale, RU_RU, AssetMissing, AssetUnreadable, BuildMismatch, BundledTalentLocalizationRepository, ClassMismatch, ContentHashMismatch (+27 more)

### Community 86 - "Allocation Engine State Decisions"
Cohesion: 0.10
Nodes (5): AllocationRules, TreeIndex, AllocationDecision, AllocationResult, BuildValidation

### Community 87 - "Release APK verification tests"
Cohesion: 0.11
Nodes (33): apksigner_output(), evaluate(), failed(), keytool_form(), library(), test_16kb_aligned_64_bit_libraries_pass(), test_4kb_or_unreadable_or_misplaced_64_bit_libraries_fail(), test_a_2048_bit_key_fails_the_strategy() (+25 more)

### Community 89 - "Python allocation conformance execution"
Cohesion: 0.09
Nodes (28): _add_prerequisite(), _add_unresolved_condition(), apply_mutations(), ConformanceSuite, DatasetRef, engine_for(), Expectation, ExpectedIssue (+20 more)

### Community 90 - "Store delivery workflow configuration"
Cohesion: 0.05
Nodes (42): AAB, ANDROID_RELEASE_CERT_SHA256, ANDROID_RELEASE_KEY_ALIAS, ANDROID_RELEASE_KEYSTORE_BASE64, ANDROID_RELEASE_KEYSTORE_PASSWORD, ANDROID_UPLOAD_CERT_SHA256, ANDROID_UPLOAD_KEY_ALIAS, ANDROID_UPLOAD_KEYSTORE_BASE64 (+34 more)

### Community 91 - "Utc Instant"
Cohesion: 0.08
Nodes (16): isRetryableUrlError(), IosOtaAdapterTest, Entry, InvalidKeyringException, KeyringProblem, BAD_KEY_ID, DUPLICATE_KEY_ID, NOT_A_P256_KEY (+8 more)

### Community 92 - "ota historical replay py"
Cohesion: 0.09
Nodes (20): CatalogRead, Check, checkout_state(), _git(), guard_production(), _load_stand(), main(), _now() (+12 more)

### Community 93 - "Client Version"
Cohesion: 0.08
Nodes (23): BatchClassResult, normalize_batch(), LocalCsvDb2Source, at_repo_root(), build(), documents(), english_source(), inputs() (+15 more)

### Community 94 - "i OS OTA simulator harness"
Cohesion: 0.09
Nodes (19): app(), app_env(), _ca(), closed_port(), HarnessError, inside_git_checkout(), _leaf(), main() (+11 more)

### Community 95 - "visuals pipeline py"
Cohesion: 0.12
Nodes (26): sha256_of(), build_icon_registry(), convert_blp2(), ConvertedIcon, converter_description(), icon_file_name(), IconAssetError, read_blp2_size() (+18 more)

### Community 97 - "HTTPS staging stand tests"
Cohesion: 0.10
Nodes (23): tls_handshake(), _trust_context(), _catalog(), _get(), _keyring(), _NoRedirect, origin(), out() (+15 more)

### Community 98 - "Talent Tree Viewport"
Cohesion: 0.10
Nodes (8): animateCamera(), TalentTreeLayoutClass, LANDSCAPE, PORTRAIT, TABLET, nodeAtScreen(), TalentTreeViewport, TalentTreeShowFocusTest

### Community 100 - "Freshness Git Hub issue delivery"
Cohesion: 0.10
Nodes (13): GitHubIssues, GitHubIssuesError, Issue, _issue(), _message(), _apply(), _default_run_url(), _latest() (+5 more)

### Community 101 - "Canonical mobile bundle synchronization"
Cohesion: 0.10
Nodes (19): bundled_builds_drift(), BundleKind, check(), check_kotlin_bundled_builds(), directory_drift(), drift(), _file_count(), kotlin_bundled_builds() (+11 more)

### Community 102 - "Product dataset regression validation"
Cohesion: 0.08
Nodes (16): _print_report(), ValidationIssue, _find_cycle(), visit(), _validate_grid(), _validate_sections(), _validate_tree(), ValidationReport (+8 more)

### Community 103 - "Notification delivery recovery tests"
Cohesion: 0.13
Nodes (24): load_dedup(), save_dedup(), _candidate_run(), cli_env(), FakeTelegram, freshness_args(), git(), _pending() (+16 more)

### Community 104 - "Module Readiness"
Cohesion: 0.17
Nodes (29): Readiness, commit_on_main(), FakeRunner, gates(), kinds(), main_with_visuals(), run_prepare(), services() (+21 more)

### Community 105 - "Talent accessibility localized text"
Cohesion: 0.06
Nodes (5): MissingValuesNote, CHARACTER_DEPENDENT, NOT_IN_CLIENT_DATA, TalentNodeAccessibilityCopy, text()

### Community 108 - "Remote staging device checks"
Cohesion: 0.12
Nodes (5): Counting, CountingTls, Device, OtaRemoteStagingCheck, BlobFetcher

### Community 109 - "Build State"
Cohesion: 0.10
Nodes (7): run_step(), TalentAllocationEngine, BuildState, _exhausted(), golden_build(), test_build_state_is_canonical_and_immutable(), test_build_state_json_round_trip_uses_camel_case()

### Community 110 - "New build class selection"
Cohesion: 0.09
Nodes (14): NewBuildClassChoice, ClassArtworkSlot, Continue, Picker, PickerCompact, platformPrefersReducedMotion(), prefersReducedMotion(), ClassCard() (+6 more)

### Community 111 - "Talent canvas section navigation"
Cohesion: 0.08
Nodes (9): TalentMotionMetrics, talentSectionComposedPages(), talentSectionNextWarmPage(), TalentSectionPager, talentSectionPageX(), talentSectionPositionVelocity(), talentSectionReleaseTarget(), talentSectionWarmBatch() (+1 more)

### Community 112 - "Engine backed node presentation"
Cohesion: 0.14
Nodes (8): buildTalentInspectorContent(), talentNodeFacts(), tierLineOf(), buildTalentTreePresentation(), inspectorInputsOf(), syntheticDocument(), syntheticNode(), TalentNodePresentationTest

### Community 113 - "Asset Not Found Exception"
Cohesion: 0.11
Nodes (4): LocalizationCase, rankJson(), TalentLocalizationRepositoryTest, DatasetAssetSource

### Community 114 - "test ota prepare py"
Cohesion: 0.12
Nodes (28): Action, BaseKind, detect(), info(), published(), release(), test_a_base_without_a_committed_dataset_is_unknown(), test_a_build_the_verified_production_catalog_lists_is_not_prepared_again() (+20 more)

### Community 115 - "Mobile bundle synchronization tests"
Cohesion: 0.07
Nodes (14): drifted(), load_script(), mirror(), no_network(), snapshot(), test_a_drifted_runtime_bundled_build_list_fails_every_check(), test_committed_bundle_matches_canonical_without_network(), test_failed_preflight_for_a_missing_canonical_file_changes_nothing() (+6 more)

### Community 116 - "Localization Load Error"
Cohesion: 0.08
Nodes (32): LocalizationProvenance, LocalizationSourceTable, LocalizationTableLocale, EN_US, RU_RU, LocalizedDescription, LocalizedDescriptionSource, SPELL_DESCRIPTION (+24 more)

### Community 119 - "Talent data contract architecture"
Cohesion: 0.07
Nodes (30): Python data platform and KMP ownership boundaries, Read-only exact-dataset import preparation, Share UI snapshot ownership, talentforge-share.v1 canonical share codes, BuildSource → Db2Source → TraitDataSet → normalized dataset, Semantic build diff and diagnostic noise classification, Node-centric talent-data.v1 and dataset manifest, Daily dataset freshness watcher (+22 more)

### Community 121 - "Inspector sequential reading state"
Cohesion: 0.19
Nodes (5): compactScenarios(), InspectorScenario, unlockedScenario(), Session, TalentInspectorReadingUiTest

### Community 122 - "Tooltip renderer failure tests"
Cohesion: 0.07
Nodes (12): rank_text(), test_a_missing_referenced_spell_is_missing_data(), test_conditional_with_different_branches_never_picks_one(), test_conditional_with_identical_branches_needs_no_choice(), test_nested_tooltip_cycle_fails_closed(), test_nested_tooltip_depth_is_bounded(), test_no_rendered_segment_contains_template_markup(), test_radius() (+4 more)

### Community 123 - "Historical replay automation workflow"
Cohesion: 0.06
Nodes (34): concurrency, cancel-in-progress, group, REPLAY_EXIT, jobs, replay, name, on (+26 more)

### Community 124 - "Localization Load Error"
Cohesion: 0.12
Nodes (25): bodyOf(), buildTalentInspectorDescription(), changedValueIndices(), DescriptionBody, DescriptionSpan, Missing, nextRankComparison(), NextRankText (+17 more)

### Community 125 - "Staged Update"
Cohesion: 0.08
Nodes (7): Replace, StagingStand, Stall, StandFault, StandOrigin, Status, Truncate

### Community 127 - "Bundled Talent Dataset Repository"
Cohesion: 0.18
Nodes (3): OverridingAssetSource, TalentLocalizationUiTest, WarriorText

### Community 128 - "Allocation conformance scenario authoring"
Cohesion: 0.12
Nodes (17): Step, NodeAllocation, NodeRef, main(), Author, build_suite(), class_scenarios(), Fill (+9 more)

### Community 129 - "Store Delivery Revision Gate Tests"
Cohesion: 0.14
Nodes (27): asc_build(), decide(), decide_ios(), ios_record(), load(), pem(), play_state(), record() (+19 more)

### Community 130 - "Build import confirmation tests"
Cohesion: 0.19
Nodes (3): BuildImportFlowTest, TalentDatasetRepository, TalentDatasetRepository

### Community 131 - "Talent canvas gesture tests"
Cohesion: 0.21
Nodes (3): productState(), Session, TalentTreeGesturesUiTest

### Community 132 - "Allocation issue canonical ordering"
Cohesion: 0.07
Nodes (26): AllocationIssue, AllocationIssueCode, DEPENDENT_NODE_WOULD_BREAK, DUPLICATE_ALLOCATION, ENTRY_NOT_SELECTED, INVALID_BUILD_STATE, INVALID_ENTRY, MAX_RANK_REACHED (+18 more)

### Community 133 - "Bundled dataset integrity tests"
Cohesion: 0.14
Nodes (4): BundledRepositoryTest, DatasetAssetSource, DatasetAssetSource, DatasetAssetSource

### Community 134 - "Product dataset regression validation"
Cohesion: 0.07
Nodes (15): at_repo_root(), committed(), previous(), _spend_all_points(), test_a_previous_product_build_validates_alike_on_both_builds(), test_class_shape_and_point_budget(), test_committed_document_is_reproduced_from_the_build_rows(), test_engine_supports_every_node_except_the_known_unresolved_venom() (+7 more)

### Community 135 - "OTA editor dataset leases"
Cohesion: 0.09
Nodes (8): DatasetLeaseHolder, diagnostic(), EditorDatasetAccess, EditorDatasetResolution, Ready, Slot, text(), Unavailable

### Community 136 - "Localized share import messages"
Cohesion: 0.07
Nodes (14): ClassUnavailable, ClientBuildUnavailable, ClipboardEmpty, ClipboardUnavailable, Damaged, DatasetProblem, decodeMessageOf(), EmptyInput (+6 more)

### Community 137 - "Bundled talent icon loading"
Cohesion: 0.13
Nodes (11): BundledTalentIconSource, decodeBundledIcon(), Failed, Loaded, TalentIconFailure, ASSET_MISSING, ASSET_UNREADABLE, UNDECODABLE (+3 more)

### Community 138 - "Talent inspector presentation facts"
Cohesion: 0.12
Nodes (27): ActionNotAvailable, Available, Blocked, InProgress, InspectorText, Maxed, MaxRankReached, NeededBy (+19 more)

### Community 139 - "Ota Fetcher Transport Test"
Cohesion: 0.13
Nodes (7): BrokenTls, Certificate, Counting, OtaFetcherTransportTest, ResettingOrigin, SilentOrigin, TlsOrigin

### Community 140 - "Inspector adaptive layout tests"
Cohesion: 0.22
Nodes (3): scenarioOf(), Session, TalentInspectorAdaptiveUiTest

### Community 141 - "Tooltip Template Token Rendering"
Cohesion: 0.16
Nodes (7): TooltipGap, _Context, _normalize(), _Output, _PluralState, _rank(), TalentRenderer

### Community 142 - "Python purchase refund tests"
Cohesion: 0.16
Nodes (21): node(), plan(), spend_all(), test_actions_on_an_invalid_state_are_refused(), test_all_failing_rules_are_reported_together(), test_basic_purchase_costs_one_point(), test_budget_exhaustion(), test_edge_needs_fully_ranked_source() (+13 more)

### Community 143 - "Local build repository management"
Cohesion: 0.08
Nodes (7): platformBuildClock(), platformBuildClock(), rememberTalentTreeSectionCameras(), platformBuildClock(), platformBuildClock(), BuildClock, RandomLocalBuildIdGenerator

### Community 144 - "License notice generation tests"
Cohesion: 0.09
Nodes (16): ar(), committed(), fat(), load_generator(), skiko_content(), test_a_family_or_own_archive_the_klib_no_longer_has_fails(), test_a_new_skiko_or_stdlib_version_needs_its_own_inventory(), test_an_edited_linked_component_in_the_ios_notices_fails() (+8 more)

### Community 145 - "Russian tooltip coverage evidence"
Cohesion: 0.08
Nodes (28): 1.60.1.70009 Russian fallback reasons, 1.60.1.70009 authentic ruRU coverage, 1.60.1.70009 English rank-description coverage, 1.60.1.70009 explicit tooltip gaps, 1.60.1.70245 Russian fallback reasons, 1.60.1.70245 authentic ruRU coverage, 1.60.1.70245 English rank-description coverage, 1.60.1.70245 explicit tooltip gaps (+20 more)

### Community 146 - "i OS OTA staging scenarios"
Cohesion: 0.23
Nodes (4): IosOtaStagingE2ETest, KillingStore, PhaseNote, RecordingAssetSource

### Community 147 - "Talent Section Model Projection"
Cohesion: 0.13
Nodes (16): buildTalentTreeSections(), DuplicateSectionIndex, MembershipMismatch, ModelMismatch, NodeWithoutSection, NoSections, sectionBoundsOf(), Sectioned (+8 more)

### Community 149 - "Strict duplicate JSON detection"
Cohesion: 0.11
Nodes (17): Failure, Frame, JsonDuplicateNames, JsonScan, JsonViolation, BYTE_ORDER_MARK, CONTROL_CHARACTER, DUPLICATE_MEMBER_NAME (+9 more)

### Community 152 - "Talent Allocation Engine"
Cohesion: 0.08
Nodes (10): AllocationDecision, AllocationIssueCode, AllocationResult, BuildFile, BuildValidation, _Model, reason_codes(), test_build_file_round_trip() (+2 more)

### Community 153 - "Store screenshot verification tests"
Cohesion: 0.25
Nodes (25): chunk(), failed(), load_validator(), make_pack(), png(), replace(), test_a_complete_pack_passes(), test_a_damaged_png_fails() (+17 more)

### Community 154 - "Talent entry numeric serialization"
Cohesion: 0.11
Nodes (12): Decimal, Integral, NumberValue, NumberValueSerializer, PerRankStatus, INCOMPLETE, NONE, RESOLVED (+4 more)

### Community 155 - "Production OTA monitoring workflow"
Cohesion: 0.07
Nodes (29): concurrency, cancel-in-progress, group, GITHUB_TOKEN, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, jobs, watch (+21 more)

### Community 156 - "Ios Ota Device"
Cohesion: 0.13
Nodes (8): Arm, CONTROL, RETRY, Check, CountingTls, OtaRetryRemoteMeasurement, Recording, Try

### Community 159 - "Tooltips Load Error"
Cohesion: 0.15
Nodes (21): AssetMissing, AssetUnreadable, BuildMismatch, BundledTalentVisualsRepository, ClassMismatch, DatasetRevisionMismatch, DuplicateEntry, DuplicateIcon (+13 more)

### Community 160 - "OTA signing and staging keys"
Cohesion: 0.19
Nodes (15): fault_bad_signature(), fault_expired(), fault_restore(), _fetch(), HelperError, inside_git_checkout(), keygen(), main() (+7 more)

### Community 161 - "Talent Tree Viewport State"
Cohesion: 0.13
Nodes (10): sidePanelWouldCover(), rememberTalentTreeViewportState(), TalentTreeFitPolicy, SECTION, WHOLE_TREE, TalentTreeFocusMode, CENTRE, REVEAL (+2 more)

### Community 163 - "Game Id"
Cohesion: 0.30
Nodes (5): BuildImportPreparation, prepareBuildImport(), Ready, BuildImportTest, RecordingDatasets

### Community 164 - "Play Bundle Release Verification"
Cohesion: 0.20
Nodes (22): evaluate(), failed(), libraries(), load_validator(), manifest_with(), signature(), test_32_bit_without_64_bit_fails(), test_4kb_aligned_64_bit_library_fails() (+14 more)

### Community 165 - "Build Library Storage"
Cohesion: 0.10
Nodes (5): DesktopBuildLibraryStorage, main(), IosBuildLibraryStorage, BuildLibraryReadException, BuildLibraryWriteException

### Community 166 - "OTA Preparation Workflow Variables"
Cohesion: 0.07
Nodes (27): ARTIFACT, ARTIFACT_NAME, DETECT_ACTION, DETECT_EXIT, DETECT_KEY, DOWNLOAD_OUTCOME, GH_TOKEN, GITHUB_TOKEN (+19 more)

### Community 167 - "Tablet Section Header Tests"
Cohesion: 0.28
Nodes (4): talentTabletSectionHeaderTag(), withSectionPoints(), TalentTabletSectionHeadersUiTest, Window

### Community 168 - "Talent Node Type"
Cohesion: 0.14
Nodes (19): CrossSectionFixture, containsRequiredPrerequisiteCycle(), duplicateCodes(), structuralErrorCodes(), TalentEntry, Prerequisite, PrerequisiteKind, REQUIRED (+11 more)

### Community 169 - "i OS OTA simulator harness"
Cohesion: 0.14
Nodes (8): active(), app_scenarios(), AppRun, fixture_test_key(), inet_sockets(), pick_device(), run_command(), Stand

### Community 170 - "Google Play Delivery API"
Cohesion: 0.15
Nodes (10): api_error(), check_play_track(), decide_play(), delivered(), Decision, GooglePlay, play_check(), play_deliver() (+2 more)

### Community 171 - "App Store Metadata Tests"
Cohesion: 0.22
Nodes (19): failed(), load_validator(), pack(), test_a_missing_field_fails(), test_an_ota_off_claim_fails(), test_categories(), test_character_limits(), test_cli_writes_a_report_and_exits_by_result() (+11 more)

### Community 172 - "Localized Class Build Titles"
Cohesion: 0.09
Nodes (4): buildDisplayTitle(), ClassCopy, classNameResourceFor(), localizedClassName()

### Community 173 - "Store Delivery Workflow Steps"
Cohesion: 0.16
Nodes (26): steps, steps, steps, steps, steps, steps, steps, id (+18 more)

### Community 174 - "Sharing And Clipboard Contracts"
Cohesion: 0.10
Nodes (17): Cancelled, ClipboardRead, ClipboardWrite, Completed, Copied, CopiedToClipboard, Empty, Failed (+9 more)

### Community 176 - "decode Talent Data Document"
Cohesion: 0.15
Nodes (3): ContractParsingSmokeTest, AllocationConformanceReplayTest, ConformanceContractTest

### Community 177 - "i OS Project Release Validation"
Cohesion: 0.15
Nodes (13): Check, _configurations(), _dict(), evaluate(), Evaluation, format_summary(), _info_checks(), _list() (+5 more)

### Community 178 - "App Store Test Flight Delivery"
Cohesion: 0.15
Nodes (7): AppStoreConnect, asc_check(), AscBuild, decide_ios(), DeliveryError, _moment(), testflight_distribute()

### Community 179 - "Rank tooltip value evaluation"
Cohesion: 0.23
Nodes (4): SpellEffectValues, Ref, Failure, context()

### Community 180 - "sha256 ref"
Cohesion: 0.14
Nodes (17): test_artifact_identity_is_not_just_its_name(), test_artifact_must_belong_to_pinned_run(), test_copied_staging_evidence_never_coerces_boolean_to_integer(), test_correct_name_cannot_hide_changed_archive(), test_exact_package_has_positive_control(), test_forged_central_directory_count_is_rejected_before_zipfile_allocation(), test_hostile_archive_is_refused(), test_indexed_artifact_code_is_never_executed() (+9 more)

### Community 181 - "Product dataset regression validation"
Cohesion: 0.10
Nodes (11): at_repo_root(), committed(), _nodes(), previous(), _spend_until_maxed(), test_a_previous_product_build_can_stay_valid_and_still_change_meaning(), test_a_previous_product_build_is_not_valid_on_the_product_build(), test_class_shape_and_point_budget() (+3 more)

### Community 182 - "OTA update lifecycle coordination"
Cohesion: 0.16
Nodes (8): InstallProgress, Activated, CheckOutcome, Current, Failed, NeedsNewerApp, OtaStatus, OtaUpdateCoordinator

### Community 184 - "Asset Not Found Exception"
Cohesion: 0.19
Nodes (4): resolveClassTalentText(), TalentClassText, TalentEntryText, TalentClassTextTest

### Community 185 - "visuals pipeline py"
Cohesion: 0.20
Nodes (18): compare_visuals(), committed_files(), decoded(), encode(), registry_of(), test_a_changed_client_file_blocks_even_with_the_same_pixels(), test_a_changed_sidecar_blocks(), test_a_regeneration_on_linux_holds_the_same_visuals() (+10 more)

### Community 186 - "Android Section Interaction Scenarios"
Cohesion: 0.24
Nodes (8): Check, back(), close_reopen(), create(), panel_and_tabs(), relaunch(), rotate_selected(), spend()

### Community 187 - "Freshness Tracking Issue State"
Cohesion: 0.20
Nodes (20): FreshnessDocument, _append(), _body(), _build_number(), CheckSnapshot, _close_comment(), _fact(), _facts_table() (+12 more)

### Community 188 - "i OS Project Validator Tests"
Cohesion: 0.20
Nodes (17): edit_info(), edit_pbxproj(), failed(), load_validator(), project(), test_another_bundle_id_fails(), test_cli_writes_a_report_and_exits_by_result(), test_committed_signing_settings_fail() (+9 more)

### Community 189 - "Build Import Controller State"
Cohesion: 0.12
Nodes (13): BuildImportController, Checking, ClipboardEmpty, ClipboardUnavailable, Editing, EmptyInput, Imported, ImportPhase (+5 more)

### Community 190 - "HTTPS staging stand tests"
Cohesion: 0.11
Nodes (8): format_date(), has_early_utf8_meta(), http_fetch(), main(), MetaCharsetParser, render(), ToolError, verify_live()

### Community 192 - "Client Layout Grid Fitting"
Cohesion: 0.17
Nodes (10): Axis, dominant_step(), fit_grid(), Grid, _mode(), test_nudged_column_does_not_collapse_grid(), test_nudged_first_row_does_not_shift_all_rows(), test_regular_grid() (+2 more)

### Community 193 - "Module json"
Cohesion: 0.13
Nodes (18): android_version(), ios_version(), read_versions(), show(), Versions, build_inventory(), _classify_ref(), _closing() (+10 more)

### Community 194 - "Localized Build Recency Text"
Cohesion: 0.11
Nodes (10): BuildRecency, buildRecencyOf(), Days, Hours, JustNow, LastWeek, Minutes, Weeks (+2 more)

### Community 196 - "Share Code Binary Primitives"
Cohesion: 0.13
Nodes (10): ShareCodeBase32, ShareCodeCrc32, ShareCodeReader, ShareCodeReadException, ShareCodeReadFailure, MALFORMED_NUMBER, TRUNCATED, ShareCodeWriter (+2 more)

### Community 197 - "Exact tooltip numeric formatting"
Cohesion: 0.11
Nodes (10): _decimal_string(), exact(), format_bare(), format_fixed(), round_fixed(), test_bare_numbers(), test_durations_use_the_largest_exact_unit_of_the_build_strings(), test_exact_keeps_the_source_decimal() (+2 more)

### Community 198 - "Module Path"
Cohesion: 0.16
Nodes (18): component(), edit(), klib(), notices_file(), run(), test_a_changed_or_missing_text_fails(), test_a_klib_scan_reads_included_archives_and_allows_only_cinterop_stubs(), test_a_missing_or_malformed_file_fails() (+10 more)

### Community 199 - "Platform Privacy Policy Disclosures"
Cohesion: 0.11
Nodes (18): iOS OTA network and request-log disclosure (EN), iOS private builds, sharing and backup policy (EN), iOS OTA network and request-log disclosure (RU), iOS private builds, sharing and backup policy (RU), Owner publication and byte-exact live verification, Archived local sharing and legal website disclosures (EN), Archived 19 October 2026 offline Android privacy policy (EN), Archived local sharing and legal website disclosures (RU) (+10 more)

### Community 200 - "Telegram Smoke Test Workflow"
Cohesion: 0.09
Nodes (22): TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, jobs, smoke, name, on, workflow_dispatch, permissions (+14 more)

### Community 201 - "Inspector sizing visibility rules"
Cohesion: 0.19
Nodes (5): Presentation, TalentInspectorMeasurement, TalentInspectorRevealTracker, TalentInspectorMeasureSink, TalentInspectorAdaptiveRulesTest

### Community 202 - "Talent Tree Geometry Projection"
Cohesion: 0.17
Nodes (20): boundsOf(), buildTreeUiModel(), DanglingEdgeReference, DuplicateNodeId, EmptyTree, Failed, InvalidLayout, MultipleTrees (+12 more)

### Community 203 - "Viewport Transforms And Hitboxes"
Cohesion: 0.21
Nodes (4): effectiveNodeTargetDp(), minimumNodeTargetDp(), TalentTreeHitTestTest, TalentTreeViewportTest

### Community 206 - "Talent Tablet Section Headers kt"
Cohesion: 0.17
Nodes (11): largestCluster(), SectionHeader(), Span, TalentTabletHeaderAnchor, talentTabletHeaderHeightDp(), talentTabletHeaderReserveDp(), TalentTabletHeaderSlot, talentTabletHeaderSlots() (+3 more)

### Community 208 - "test invariants py"
Cohesion: 0.14
Nodes (13): brute_force_reachable(), purchasable(), search(), _docs(), test_random_walk_invariants(), test_validate_agrees_with_brute_force_reachability(), generate(), _mutate() (+5 more)

### Community 210 - "Talent Data Contract"
Cohesion: 0.14
Nodes (8): Case, COLD, RESUME, WARM, OtaPerformanceBenchmark, Profile, Run, ThrottledOrigin

### Community 211 - "Localization Load Error"
Cohesion: 0.09
Nodes (22): TooltipTokenCategory, ARITHMETIC, COLOR, CONDITIONAL, CROSS_SPELL_DURATION, CROSS_SPELL_EFFECT_VALUE, CROSS_SPELL_PERIOD, CROSS_SPELL_PROC (+14 more)

### Community 212 - "Dependency license notice generation"
Cohesion: 0.14
Nodes (12): check(), differences(), LicenseText, LinkedComponent, main(), native_content(), NativeInventory, parse_resolution() (+4 more)

### Community 213 - "Module argparse"
Cohesion: 0.17
Nodes (12): accepted_sizes(), Check, _check_file(), evaluate(), Evaluation, format_summary(), inspect_png(), _is_laid_out() (+4 more)

### Community 214 - "Freshness Issue Transition Tests"
Cohesion: 0.26
Nodes (19): Action, decide(), read_state(), created_body(), report_of(), test_decide_a_new_product_build_with_a_newer_one_still_out_is_an_update(), test_decide_an_even_newer_build_updates_the_tracked_build_and_keeps_history(), test_decide_current_closes_any_open_tracking_issue() (+11 more)

### Community 215 - "Android Sharing Platform Adapters"
Cohesion: 0.14
Nodes (4): platformPrefersReducedMotion(), AndroidBuildCodeSharing, findActivity(), rememberPlatformBuildCodeSharing()

### Community 217 - "Android Device Check Harness"
Cohesion: 0.16
Nodes (11): adb_path(), build_apk(), dataset_sections(), DeviceError, GestureError, language_of(), main(), pick_serial() (+3 more)

### Community 219 - "visuals pipeline py"
Cohesion: 0.16
Nodes (16): check_icon_registry(), read_icon_registry(), _relative_files(), test_icon_changes_against_the_previous_build_and_the_shared_icons_pixels(), test_registry_contains_every_resolved_icon_and_nothing_else(), registry_problems(), test_duplicate_registry_keys_are_detected(), test_missing_file_is_detected() (+8 more)

### Community 220 - "Android Artifact Binary Fixtures"
Cohesion: 0.12
Nodes (12): elf_with_segments(), proto_attribute(), proto_bundle_config(), proto_element(), proto_field(), proto_manifest(), _varint(), test_bundle_config_alignments() (+4 more)

### Community 221 - "Module sys"
Cohesion: 0.15
Nodes (11): load_section_check(), resource_values(), test_english_selectors(), test_exactly_two_language_sets(), test_hierarchy_filters_control_labels_of_its_own_language(), test_primary_locale_selects_the_matching_set(), test_russian_selectors(), test_selectors_mirror_the_apps_resources() (+3 more)

### Community 222 - "Share Code Specification Tests"
Cohesion: 0.19
Nodes (15): _ascii(), _base32(), Build, decode(), _doc_codes(), encode(), _Reader, test_checksum_covers_the_prefix() (+7 more)

### Community 223 - "Build Library Storage"
Cohesion: 0.14
Nodes (4): installedAppVersion(), AndroidBuildLibraryStorage, MainActivity, AndroidOta

### Community 224 - "Mobile Architecture And Boundaries"
Cohesion: 0.23
Nodes (14): M3B TalentAllocationEngine and conformance execution, BuildAutosaveCoordinator and flush-first navigation, M5.SHARE.1/M5.SHARE.2 portable offline build sharing, mobile/composeApp: application and UI host, Canonical dataset and sidecar synchronization, M4.3 TalentBuildEditingState mutation boundary, mobile/iosApp: thin native iOS host, M5.1/M5.2a local build library and management (+6 more)

### Community 225 - "Delivery Python Setup Action"
Cohesion: 0.10
Nodes (19): description, CONFIGURED, TOOLING, inputs, tooling, name, runs, steps (+11 more)

### Community 226 - "Store Delivery Workflow Inputs"
Cohesion: 0.10
Nodes (20): mode, platforms, revision, default, description, options, required, type (+12 more)

### Community 227 - "Adaptive talent inspector components"
Cohesion: 0.31
Nodes (10): actionSize(), MaxButton(), MaxedBadge(), rankActionSemantics(), RefundButton(), SpendButton(), TalentInspectorActionMetrics, TalentInspectorCompactActions() (+2 more)

### Community 229 - "Dependency license notice generation"
Cohesion: 0.14
Nodes (11): build_notices(), Component, component_of(), declared_licenses(), _download(), embedded_notice_licenses(), NoticesError, _pom_text() (+3 more)

### Community 230 - "Module argparse"
Cohesion: 0.16
Nodes (10): Check, evaluate(), field_text(), Evaluation, format_summary(), _keyword_problems(), main(), read_field() (+2 more)

### Community 231 - "test ios privacy pages py"
Cohesion: 0.23
Nodes (12): bash(), calls(), fake_env(), sourced(), test_a_failed_check_is_retried_a_bounded_number_of_times(), test_a_failing_simctl_is_an_error_not_an_absent_app(), test_a_missing_app_is_final_and_never_retried_into_a_pass(), test_a_missing_dead_or_other_app_is_not_running() (+4 more)

### Community 232 - "Play Bundle Delivery Tests"
Cohesion: 0.31
Nodes (14): bundle_file(), deliver(), FakePlay, service_account_json(), session_location(), test_a_malformed_service_account_is_refused_without_echoing_it(), test_a_re_run_after_a_successful_upload_uploads_nothing(), test_a_session_uri_for_anything_else_is_refused_before_the_bytes_leave() (+6 more)

### Community 233 - "Telegram Recipient Setup Tests"
Cohesion: 0.25
Nodes (11): FakeClient, run_setup(), setup(), test_a_bad_selection_is_rejected_without_sending(), test_a_pick_is_explicit_and_validated(), test_listing_sends_nothing_and_chooses_nothing(), test_nothing_is_sent_without_an_explicit_yes(), test_private_chats_are_candidates_latest_message_wins() (+3 more)

### Community 234 - "Store Delivery Permissions Gate"
Cohesion: 0.12
Nodes (19): permissions, permissions, permissions, name, outputs, permissions, runs-on, timeout-minutes (+11 more)

### Community 236 - "i OS OTA platform adapters"
Cohesion: 0.18
Nodes (4): E2E, E2ESettings, E2EStand, HostFiles

### Community 238 - "Notification event deduplication"
Cohesion: 0.21
Nodes (13): CandidatePrepared, StageFailed, confirm_candidate_event(), initial_candidate(), candidate(), test_a_blocked_preparation_names_its_failed_step_once(), test_a_fresh_prepared_of_the_same_release_supersedes_the_retry(), test_a_prepared_candidate_notifies_once_per_release_id() (+5 more)

### Community 239 - "visuals pipeline py"
Cohesion: 0.15
Nodes (9): PngImage, read_png(), same_image(), _image_problem(), chunk(), filtered_row(), png_with_rows(), test_every_row_filter_is_undone() (+1 more)

### Community 241 - "Store Upload Job Orchestration"
Cohesion: 0.11
Nodes (17): environment, if, name, needs, runs-on, timeout-minutes, concurrency, cancel-in-progress (+9 more)

### Community 242 - "Store Delivery Preflight Jobs"
Cohesion: 0.11
Nodes (18): environment, if, name, needs, outputs, runs-on, timeout-minutes, environment (+10 more)

### Community 249 - "Tooltips Load Error"
Cohesion: 0.20
Nodes (3): ProductDatasetContractTest, DatasetAssetSource, DatasetAssetSource

### Community 250 - "Durable Notification Git State"
Cohesion: 0.26
Nodes (8): _commit(), download(), _git(), GitStateError, _ok(), _reapply_on_fetched_tip(), upload(), git_auth()

### Community 251 - "Python Continuous Integration Workflow"
Cohesion: 0.12
Nodes (16): runs-on, steps, jobs, checks, name, on, pull_request, push (+8 more)

### Community 252 - "OTA Preparation Workflow Inputs"
Cohesion: 0.12
Nodes (17): default, description, required, type, default, description, required, type (+9 more)

### Community 253 - "Talent gesture arbitration rules"
Cohesion: 0.17
Nodes (11): sectionSwipeModel(), talentTreeGestures(), neighbourSection(), sectionSwipeDirectionOf(), talentSectionFitSwipeDirection(), talentSectionFitSwipeLocks(), talentSectionSwipeDirection(), TalentSectionSwipeModel (+3 more)

### Community 254 - "Talent Node State"
Cohesion: 0.16
Nodes (12): nodePresentation(), TalentNodePresentation, TalentNodeState, AVAILABLE, MAXED, NO_POINTS, PARTIAL, PREREQUISITE_LOCKED (+4 more)

### Community 255 - "Landscape Tree Layout Tests"
Cohesion: 0.17
Nodes (8): TalentShowGuide, TalentTreeLandscapeHeaderMetrics, TalentTreeLandscapeTabs, TalentTreeLandscapeTabsPlacement, BELOW_HEADER, IN_HEADER, talentTreeWorkspaceLayoutClass(), TalentTreeLandscapeLayoutTest

### Community 259 - "Talent Data Contract"
Cohesion: 0.21
Nodes (5): appendCanonical(), appendString(), canonicalJson(), localizationContentHash(), LocalizationContentHashTest

### Community 260 - "Android Release APK Fixtures"
Cohesion: 0.21
Nodes (14): elf(), fake_apk(), fake_build_tools(), run_cli(), test_cli_fails_on_a_different_certificate(), test_cli_fails_on_an_unverifiable_apk(), test_cli_missing_or_non_apk_file_is_an_error(), test_cli_needs_an_expected_fingerprint() (+6 more)

### Community 261 - "Store Delivery Revision Gate Tests"
Cohesion: 0.26
Nodes (11): ci_run(), FakeGitHub, run_gate(), test_ci_counts_only_a_successful_main_run_of_the_exact_commit(), test_ci_of_another_commit_does_not_count(), test_only_records_of_this_workflow_on_main_in_this_repository_count(), test_the_gate_accepts_a_full_sha_and_a_release_tag_on_main(), test_the_gate_refuses_a_commit_or_tag_that_is_not_on_main() (+3 more)

### Community 262 - "Store Build Job Environment"
Cohesion: 0.14
Nodes (16): env, ANDROID_HOME, IOS_SDK, MODE, SOURCE_SHA, XCODE_APP, XCODE_BUILD, env (+8 more)

### Community 267 - "i OS OTA simulator harness"
Cohesion: 0.18
Nodes (6): choose_runtime(), _ios_runtimes(), QuietServer, runtime_diagnostics(), RuntimeChoice, _version_key()

### Community 268 - "Module sys"
Cohesion: 0.19
Nodes (7): _production(), test_a_cdn_failure_around_the_replay_is_blocked_with_its_report(), test_a_read_production_catalog_keeps_its_body(), test_an_unreadable_production_catalog_is_recorded_not_raised(), test_g2_passes_only_when_both_reads_agree(), test_the_entrypoint_refuses_an_output_inside_git_or_not_empty(), test_the_offline_phases_end_to_end()

### Community 269 - "Store Delivery Orchestration Tests"
Cohesion: 0.23
Nodes (15): gate(), needs(), status(), summarize(), test_a_failed_build_holds_the_other_platform_back(), test_a_failed_gate_blocks_every_selected_platform(), test_a_failed_job_wins_over_an_earlier_success_status(), test_a_store_command_without_credentials_is_refused() (+7 more)

### Community 270 - "Play Bundle Signing Tests"
Cohesion: 0.23
Nodes (12): fake_bundle(), fake_tools(), keytool_form(), printcert_output(), run_cli(), test_cli_fails_a_bundle_signed_with_the_app_signing_key(), test_cli_needs_both_fingerprints(), test_cli_pass_writes_a_report_without_secrets() (+4 more)

### Community 272 - "OTA Publication And Release Readiness"
Cohesion: 0.15
Nodes (9): Channel-isolated immutable S3 publication safety, Gated OTA publication and exact-byte approval, OTA rollback, catalog renewal and key-loss recovery, Release approval pins exact manifest bytes, Isolated real-TLS staging stand, Remote VK Cloud public staging origin, Recorded 70450 to 70532 candidate verification, One-SHA base/candidate OTA staging verification (+1 more)

### Community 276 - "decode Talent Data Document"
Cohesion: 0.26
Nodes (5): conformanceWithout(), documentWithout(), removePath(), RequiredFieldSemanticsTest, withoutPath()

### Community 277 - "ota historical replay py"
Cohesion: 0.13
Nodes (7): bytes_files(), committed_files(), export_build(), _git(), private_keys(), release_files(), store_problems()

### Community 278 - "Freshness Report Consistency Validation"
Cohesion: 0.19
Nodes (7): BuildFact, _check_consistency(), CheckFailure, parse_document(), read_result(), _Strict, test_the_real_unknown_output_is_read()

### Community 279 - "i OS Simulator Runtime Selection"
Cohesion: 0.27
Nodes (11): device(), listing(), load_harness(), runtime(), test_a_requested_device_no_runtime_supports_is_never_replaced(), test_an_unavailable_runtime_is_never_chosen(), test_both_runtimes_support_the_device_and_the_newest_wins(), test_no_ios_runtime_at_all_is_diagnosed() (+3 more)

### Community 280 - "Thin Swift UI Application Host"
Cohesion: 0.19
Nodes (5): ComposeApp, ContentView, iOSApp, .body, SwiftUI

### Community 281 - "Integration Test Workflow"
Cohesion: 0.14
Nodes (13): runs-on, steps, jobs, integration, name, on, workflow_dispatch, name (+5 more)

### Community 282 - "Asset Not Found Exception"
Cohesion: 0.23
Nodes (3): LocalizedTalentEntry, TalentLocalization, RepoDataAssets

### Community 286 - "Game Id"
Cohesion: 0.27
Nodes (3): ClassTokenSerializer, ContentHashSerializer, GameIdSerializer

### Community 287 - "Asset Not Found Exception"
Cohesion: 0.29
Nodes (8): entries(), obj(), rehashed(), Warrior, with(), withEntries(), withEntry(), without()

### Community 290 - "Module json"
Cohesion: 0.27
Nodes (8): parse_request(), exact_package(), request_wire(), test_request_duplicate_members_fail_at_any_depth(), test_request_roundtrips_and_is_frozen(), test_request_wire_is_closed(), test_required_request_members_cannot_be_omitted(), test_run_wire_is_closed()

### Community 291 - "Real Allocation Structure Regressions"
Cohesion: 0.18
Nodes (7): fill_section(), test_druid_required_edge(), test_hunter_cycle_cannot_support_itself(), test_hunter_cycle_is_not_a_deadlock(), test_hunter_cycle_is_the_real_data(), test_misplaced_duplicate_nodes_follow_the_same_rules(), test_rogue_venom_fails_closed_on_unresolved_condition()

### Community 293 - "OTA Preparation Workflow Triggers"
Cohesion: 0.15
Nodes (12): concurrency, cancel-in-progress, group, jobs, prepare, name, on, workflow_run (+4 more)

### Community 294 - "OTA Preparation Artifact Settings"
Cohesion: 0.15
Nodes (13): with, cache, fetch-depth, github-token, if-no-files-found, name, overwrite, path (+5 more)

### Community 296 - "Responsive Action Button Labels"
Cohesion: 0.22
Nodes (5): ActionButtonLabelTest, Kind, Primary, Secondary, Rendered

### Community 299 - "Game Id"
Cohesion: 0.32
Nodes (10): TalentIconAsset, TalentIconImageFormat, PNG, TalentIconRegistry, TalentIconSourceFormat, BLP2, TalentIconsProvenance, TalentVisualEntry (+2 more)

### Community 302 - "Candidate pull request tests"
Cohesion: 0.18
Nodes (5): git(), repo(), advance_main(), checkout(), commit()

### Community 303 - "Freshness Notification Workflow"
Cohesion: 0.18
Nodes (12): notify, continue-on-error, environment, if, needs, permissions, runs-on, timeout-minutes (+4 more)

### Community 304 - "Candidate Proposal Job Outputs"
Cohesion: 0.17
Nodes (12): propose, candidate_artifact, candidate_run_id, outcome, pr_url, uploaded_artifact, outputs, if (+4 more)

### Community 305 - "Release Package Workflow Configuration"
Cohesion: 0.17
Nodes (11): concurrency, cancel-in-progress, group, jobs, name, on, pull_request, permissions (+3 more)

### Community 307 - "Talent Tree"
Cohesion: 0.33
Nodes (6): PointBudget, Provenance, TalentLayout, TalentSection, TalentTree, VisualOnlyEdge

### Community 310 - "Play Signing Self Tests"
Cohesion: 0.30
Nodes (10): cleanup(), die(), gradle(), make_key(), nothing_in(), result(), selftest(), play_signing_selftest.sh script (+2 more)

### Community 311 - "Test Flight Distribution API Tests"
Cohesion: 0.32
Nodes (8): asc_client(), distribute(), FakeAsc, test_a_group_with_access_to_all_builds_needs_no_assignment(), test_a_processed_build_is_given_to_the_internal_group(), test_testflight_refusals(), test_the_app_store_connect_token_is_a_valid_es256_jwt(), test_the_asc_check_reads_versions_and_numbers()

### Community 312 - "OTA Publication And Release Readiness"
Cohesion: 0.31
Nodes (3): Durable at-least-once delivery and episode deduplication, Owner Telegram events from existing workflows, Main-only notification secrets and failure isolation

### Community 313 - "Mobile CI Dependency Setup"
Cohesion: 0.18
Nodes (11): with, cache, distribution, fetch-depth, if-no-files-found, java-version, name, path (+3 more)

### Community 314 - "Release Package Build Inputs"
Cohesion: 0.18
Nodes (11): default, description, required, type, description, required, type, base (+3 more)

### Community 315 - "Staging Verification Dependency Setup"
Cohesion: 0.18
Nodes (11): with, cache, distribution, fetch-depth, if-no-files-found, java-version, name, path (+3 more)

### Community 318 - "Tooltips Load Error"
Cohesion: 0.18
Nodes (11): ContractKind, ALLOCATION_BUILD, ALLOCATION_CONFORMANCE, DATASET_MANIFEST, TALENT_DATA, TALENT_ICONS, TALENT_LOCALIZATION, TALENT_TOOLTIPS (+3 more)

### Community 319 - "Game Id"
Cohesion: 0.24
Nodes (9): BuildImportFailure, ClassUnavailable, ClientBuildUnavailable, datasetFailure(), DatasetRejected, EngineUnavailable, InvalidAllocation, Rejected (+1 more)

### Community 320 - "i OS OTA simulator harness"
Cohesion: 0.31
Nodes (7): handler(), apply(), do_GET(), drop(), send(), serve(), Route

### Community 322 - "Scheduled Dataset Freshness Checks"
Cohesion: 0.20
Nodes (9): concurrency, cancel-in-progress, group, jobs, name, on, schedule, workflow_dispatch (+1 more)

### Community 323 - "Mac Mobile CI Environment"
Cohesion: 0.22
Nodes (10): ANDROID_HOME, IOS_SDK, IOS_SIMULATOR_SDK, XCODE_APP, XCODE_BUILD, env, mobile-macos, env (+2 more)

### Community 324 - "Mobile CI Production OTA"
Cohesion: 0.36
Nodes (10): TALENTFORGE_OTA_IOS_PRODUCTION_ENABLED, steps, steps, steps, env, if, name, run (+2 more)

### Community 325 - "Candidate Preparation Workflow Steps"
Cohesion: 0.36
Nodes (10): steps, steps, steps, continue-on-error, id, if, name, run (+2 more)

### Community 326 - "Release Package Job Steps"
Cohesion: 0.20
Nodes (10): package, runs-on, steps, timeout-minutes, id, if, name, run (+2 more)

### Community 327 - "Staging Workflow Triggers"
Cohesion: 0.20
Nodes (9): concurrency, cancel-in-progress, group, name, on, pull_request, permissions, contents (+1 more)

### Community 334 - "Canonical mobile bundle synchronization"
Cohesion: 0.24
Nodes (5): main(), product_build(), main(), test_the_script_adds_the_product_bundle(), runner()

### Community 335 - "i OS Simulator Smoke Runner"
Cohesion: 0.38
Nodes (9): check_app_running(), cleanup_device(), find_device(), main(), running_pid_in_listing(), save_diagnostics(), ios_simulator_smoke.sh script, smoke_device() (+1 more)

### Community 336 - "Release Signing Self Tests"
Cohesion: 0.38
Nodes (8): die(), gradle(), nothing_staged(), publish_good(), result(), release_signing_selftest.sh script, validate(), with_signing()

### Community 337 - "Freshness Check Workflow Steps"
Cohesion: 0.33
Nodes (9): steps, steps, continue-on-error, id, if, name, run, shell (+1 more)

### Community 338 - "Freshness Workflow Artifact Setup"
Cohesion: 0.22
Nodes (9): with, cache, if-no-files-found, name, path, persist-credentials, python-version, ref (+1 more)

### Community 339 - "Release Package Environment Inputs"
Cohesion: 0.22
Nodes (9): BASE, BUILD, GH_TOKEN, PACKAGE_EXIT, RELEASE_REVISION, RUN_URL, SOURCE_REVISION, STAGING_RUN (+1 more)

### Community 340 - "Release Package Artifact Setup"
Cohesion: 0.22
Nodes (9): with, cache, fetch-depth, if-no-files-found, name, path, persist-credentials, python-version (+1 more)

### Community 341 - "i OS Store Upload Job"
Cohesion: 0.22
Nodes (9): environment, if, name, needs, outputs, runs-on, timeout-minutes, ios-upload (+1 more)

### Community 342 - "JSON Scanner Parsing States"
Cohesion: 0.22
Nodes (8): Expect, COLON, COMMA_OR_END, DONE, NAME, NAME_OR_END, VALUE, VALUE_OR_END

### Community 343 - "Dependency license notice generation"
Cohesion: 0.22
Nodes (4): archive_members(), arm64_slice(), families(), klib_native_content()

### Community 345 - "ota historical replay py"
Cohesion: 0.28
Nodes (3): compare_files(), evidence_problems(), Subject

### Community 346 - "Tooltip template grammar parsing"
Cohesion: 0.53
Nodes (8): Binary, parse_expression(), expr(), factor(), peek(), take(), term(), test_expressions_have_precedence_and_no_eval()

### Community 347 - "Mobile CI Workflow Configuration"
Cohesion: 0.25
Nodes (7): concurrency, cancel-in-progress, group, jobs, name, permissions, contents

### Community 354 - "Freshness Notification Environment"
Cohesion: 0.29
Nodes (7): CHECK_EXIT_CODE, GH_TOKEN, GITHUB_TOKEN, ISSUE_URL, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, env

### Community 355 - "Preparation Notification Job"
Cohesion: 0.29
Nodes (7): notify, continue-on-error, environment, if, needs, runs-on, timeout-minutes

### Community 356 - "Staging Verification Environment Inputs"
Cohesion: 0.29
Nodes (7): BASE, CANDIDATE, EVENT, RERUN_EXIT, REVISION, VERIFY_EXIT, env

### Community 357 - "Staging Source Revision Input"
Cohesion: 0.29
Nodes (7): revision, workflow_dispatch, default, description, required, type, inputs

### Community 358 - "Staging Verification Workflow Steps"
Cohesion: 0.29
Nodes (7): id, if, name, run, shell, uses, steps

### Community 359 - "Android Store Build Job"
Cohesion: 0.29
Nodes (7): environment, if, name, needs, runs-on, timeout-minutes, android-build

### Community 360 - "Talent gesture arbitration rules"
Cohesion: 0.33
Nodes (3): talentSectionHeldPosition(), TalentSectionOverscroll, talentSectionSwipeFeedbackDp()

### Community 365 - "Local HTTPS staging stand"
Cohesion: 0.43
Nodes (5): _handler(), do_GET(), do_HEAD(), do_POST(), _status()

### Community 366 - "store delivery py"
Cohesion: 0.38
Nodes (5): b64url(), es256_jwt(), _jwt_parts(), _jwt_segment(), rs256_jwt()

### Community 367 - "Store Artifact OTA Validation"
Cohesion: 0.60
Nodes (5): android_files(), check_android(), test_a_release_that_is_not_one_ota_on_release_fails(), test_apk_and_aab_of_one_ota_on_release_pass(), test_the_dex_must_carry_production_ota_and_no_staging()

### Community 368 - "Linux Mobile CI Job"
Cohesion: 0.33
Nodes (6): run, mobile-linux, defaults, name, runs-on, shell

### Community 369 - "Mobile CI Event Triggers"
Cohesion: 0.33
Nodes (6): on, pull_request, push, workflow_dispatch, paths, branches

### Community 370 - "Candidate Workflow Permission Boundaries"
Cohesion: 0.47
Nodes (6): permissions, permissions, actions, contents, pull-requests, permissions

### Community 374 - "i OS Delivery Signing Setup"
Cohesion: 0.60
Nodes (5): cleanup(), die(), install(), plist_value(), ios_delivery_signing.sh script

### Community 375 - "Store Delivery Repository Fixtures"
Cohesion: 0.33
Nodes (4): git(), p256_key(), repo(), rsa_key()

### Community 376 - "Freshness Check Job Outputs"
Cohesion: 0.40
Nodes (5): outputs, runs-on, timeout-minutes, freshness, check_exit_code

### Community 377 - "Release Revision Workflow Input"
Cohesion: 0.40
Nodes (5): release_revision, default, description, required, type

### Community 378 - "Release Source Revision Input"
Cohesion: 0.40
Nodes (5): source_revision, default, description, required, type

### Community 379 - "Staging Base Build Input"
Cohesion: 0.40
Nodes (5): default, description, required, type, base

### Community 380 - "Staging Candidate Build Input"
Cohesion: 0.40
Nodes (5): default, description, required, type, candidate

### Community 381 - "Inspector And Back Actions"
Cohesion: 0.40
Nodes (5): EditorBackStep, CLEAR_SELECTION, CLOSE_BUILD_ACTIONS, COLLAPSE_INSPECTOR, NAVIGATE

### Community 386 - "i OS OTA CI Job"
Cohesion: 0.50
Nodes (4): name, runs-on, timeout-minutes, ios-ota-e2e

### Community 387 - "Release Staging Evidence Input"
Cohesion: 0.50
Nodes (4): staging_run, description, required, type

### Community 388 - "Staging Verification Job Configuration"
Cohesion: 0.50
Nodes (4): jobs, verify, runs-on, timeout-minutes

### Community 389 - "Home and build library screens"
Cohesion: 0.50
Nodes (4): Duplicated, DuplicateFailed, ManagementNotice, None

### Community 390 - "Talent gesture arbitration rules"
Cohesion: 0.50
Nodes (4): TalentInspectorSwipe, DOWN, NONE, UP

### Community 392 - "i OS Catalog Fixture Vectors"
Cohesion: 0.50
Nodes (3): Case, Key, OtaCatalogFixtureVectors

### Community 395 - "OTA Integration Check Runner"
Cohesion: 0.67
Nodes (3): PYTHONPATH, ota_integration_check.sh script, step()

### Community 396 - "Tooltip Spell Reference Traversal"
Cohesion: 0.67
Nodes (3): referenced_spells(), walk(), walk_expression()

### Community 402 - "Store Delivery Orchestration Tests"
Cohesion: 0.67
Nodes (3): ios_reports(), test_archive_and_export_of_one_release_pass_and_development_exports_fail(), check()

## Knowledge Gaps
- **327 isolated node(s):** `Home`, `NewBuild`, `ImportBuild`, `Licenses`, `CreatedNew` (+322 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 3533 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **135 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **How does `ClassToken` connect dataset identity, allocation, and mobile screens?**
  _High betweenness centrality (0.072) - this node is a cross-community bridge._
- **Are the 225 inferred relationships involving `TalentDataDocument` (e.g. with `apply_mutations()` and `engine_for()`) actually correct?**
  _`TalentDataDocument` has 225 INFERRED edges - heuristic or semantic inferences that need verification._
- **What connects `Home`, `NewBuild`, `ImportBuild` to the rest of the system?**
  _327 weakly-connected nodes found - possible documentation gaps or missing edges._