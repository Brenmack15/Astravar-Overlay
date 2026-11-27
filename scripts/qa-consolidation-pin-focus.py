"""Build focused methods from the unchanged native UI setup and skill journey on this QA branch."""
from pathlib import Path

ui = Path("overlay-target/src/androidTest/java/com/yazsras/astravar")
setup_file = ui / "PlayerFirstJourneyInstrumentationTest.kt"
setup = setup_file.read_text()
original = "    @Test fun characterAdvancementReviewedEquipmentCompanionAndRestart() {"
boundary = '        tap(if (ascension) "Campaign membership" else "Campaign connections")'
assert setup.count(original) == setup.count(boundary) == 1
initial = setup.split(original, 1)[1].split(boundary, 1)[0]
boundary_control = '        if (ascension) ui.openCharacterControls(product)\n'
assert initial.endswith(boundary_control)
initial = initial[:-len(boundary_control)]
assert 'tap("Confirm level 5")' in initial and initial.count('stat("HP 31 / 37")') == 2
setup_file.write_text(setup.replace(original, "    @Test fun prepareFocusedSkillActor() {" + initial + "    }\n\n" + original))

journey_file = ui / "GenesisConsolidationInstrumentationTest.kt"
journey = journey_file.read_text()
original = "    @Test fun characterArmorSkillsAndRestart() {"
before = '        capture("equipment-favorite")\n'
after = '        instrumentation.sendStatus(0, Bundle().apply { putString("stream",'
assert journey.count(original) == journey.count(before) == journey.count(after) == 1
source = journey.split(before, 1)[1].split(after, 1)[0]
assert source.count('assertPinnedSkill("Arcana")') == 2 and source.count("restart()") == 2
assert 'find(if (ascension) "Zoology native · 20" else "20")' in source
assert 'tap("Remove skill"); tap("Remove")' in source
focused = '''    @Test fun retainedSkillJourneyOnly() {
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.descriptionBounds("HP 31 / 37", product)
''' + source + "    }\n\n"
journey_file.write_text(journey.replace(original, focused + original))
