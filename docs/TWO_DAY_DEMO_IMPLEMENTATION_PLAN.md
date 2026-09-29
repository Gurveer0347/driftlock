# Two-day judge demo implementation plan — 27 September 2026

The approved PRD and Option A image define the visual and presentation scope. Build on the existing native app. Preserve the research/navigation separation and source/evidence gates.

1. Add a bounded parser for actual OSM building, park and water geometry. Add tests for source-derived features and hostile/incomplete XML. Keep the existing routing graph unchanged.
2. Render a map-first route page using actual road/route geometry, local OSM feature footprints and restrained illustrative depth. Make source/coverage and route choices clear.
3. Add a guided simulated demo progression and safe roadblock/alternate controls. The marker must remain the estimator output, with explicit GNSS state and measured summary fields only.
4. Add a separate research model page from frozen validation evidence, labeling the unresolved recorded units and failed held-cut comparison. Do not invoke the research checkpoint in Android navigation.
5. Run focused and full Android tests, build an APK, install/rehearse on the connected phone, update guide and status with actual evidence and limitations.

This plan authorizes no new data interpretation, final-test use, architecture change, remote upload, or production ML claim.
