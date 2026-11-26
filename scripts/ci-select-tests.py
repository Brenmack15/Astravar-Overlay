#!/usr/bin/env python3
"""Conservative PR plan. Selection accelerates feedback; high-risk changes require Tier B."""
import argparse
import json
import os
from pathlib import Path
import subprocess

CRITICAL = {
    'com.yazsras.astravar.data.CampaignSyncDomainTest',
    'com.yazsras.astravar.data.CampaignConnectionProjectionTest',
    'com.yazsras.astravar.data.GenesisAuthorityGuardsTest',
    'com.yazsras.astravar.data.GenesisAuthorityBackupTest',
    'com.yazsras.astravar.data.ReforgeLargeCampaignSelectionTest',
    'com.yazsras.astravar.ui.GenesisConsolidationNavigationTest',
    'com.yazsras.astravar.ui.PartyPlayerFirstTest',
    'com.yazsras.astravar.ui.CloudGameplayWorkflowsInteropTest',
    'com.yazsras.astravar.ui.CloudEncounterWorkflowInteropTest',
    'com.yazsras.astravar.ui.CloudCharacterContinuityInteropTest',
    'com.yazsras.astravar.ui.GenesisSpellReservoirUiTest',
}
GROUPS = {
    'CampaignConnection': ['data.CampaignConnectionTest', 'data.CampaignConnectionProjectionTest', 'ui.CampaignConnectionUiTest'],
    'GenesisCampaign': ['data.GenesisAuthorityRepositoryTest', 'ui.GenesisCampaignUiTest'],
    'Notion': ['data.NotionTransferRepositoryTest', 'data.NotionTransferPublicConstructorTest', 'ui.GenesisInventoryNavigationUiTest'],
    'GenesisStorage': ['data.NotionTransferRepositoryTest', 'data.NotionTransferPublicConstructorTest'],
    'GenesisInventory': ['data.GenesisInventoryTest', 'ui.GenesisInventoryNavigationUiTest', 'ui.GenesisInventoryHubUiTest'],
    'GenesisEquipment': ['data.GenesisInventoryTest', 'ui.GenesisEquipmentFavoritesUiTest'],
    'GenesisPager': ['ui.GenesisPagerTest', 'ui.GenesisCompactChromeTest'],
    'RebirthNavigation': ['ui.RebirthNavigationStateTest'],
    'GenesisCharacter': ['ui.GenesisCharacterRoutingTest', 'ui.GenesisCompactChromeTest'],
}

def plan(paths, test_files):
    available = {str(p).split('/java/', 1)[-1][:-3].replace('/', '.') for p in test_files if str(p).endswith('Test.kt')}
    selected = CRITICAL & available
    reasons = []
    for path in paths:
        name = Path(path).stem
        if path.startswith(('docs/',)) or path.endswith('.md'):
            continue
        if path.startswith(('app/src/test/',)) and path.endswith('Test.kt'):
            candidate = path.split('/java/', 1)[-1][:-3].replace('/', '.')
            if candidate in available:
                selected.add(candidate)
            else:
                reasons.append(path)  # Removed/renamed tests require the full gate.
        elif path.startswith('app/src/main/java/'):
            related = {x for x in available if x.rsplit('.', 1)[-1] in {name + 'Test', name + 'UiTest'}}
            for prefix, classes in GROUPS.items():
                if name.startswith(prefix):
                    related.update('com.yazsras.astravar.' + c for c in classes)
            selected.update(related & available)
            if '/data/' in path or not related:
                reasons.append(path)
        elif path.startswith('app/src/main/res/'):
            selected.update(x for x in available if x.endswith(('PrimaryNavigationLayoutTest', 'GenesisCompactChromeTest')))
        else:
            # Rules, schemas, dependencies, permissions, instrumentation, backend,
            # test infrastructure and unknown inputs must never silently skip Tier B.
            reasons.append(path)
    return {'full_required': bool(reasons), 'full_reasons': sorted(set(reasons)), 'tests': sorted(selected), 'changed_files': sorted(paths)}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base')
    parser.add_argument('--full', action='store_true')
    parser.add_argument('--output', default='validation/ci-plan.json')
    args = parser.parse_args()
    paths = subprocess.check_output(['git', 'diff', '--name-only', args.base, 'HEAD'], text=True).splitlines() if args.base else ['manual-full-verification']
    result = plan(paths, Path('app/src/test').rglob('*.kt'))
    result['full_required'] |= args.full
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    if os.getenv('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            stream.write('full_required=' + str(result['full_required']).lower() + '\n')
    print(json.dumps(result, indent=2))

if __name__ == '__main__':
    main()
