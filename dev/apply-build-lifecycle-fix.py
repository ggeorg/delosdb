#!/usr/bin/env python3
from pathlib import Path

path = Path('build.gradle')
if not path.exists():
    raise SystemExit('ERROR: build.gradle not found. Run this from the delosdb repository root.')

old = """tasks.named('check') {
    dependsOn 'verifyReleaseArtifacts', 'verifyReleaseDistribution', 'smoke', 'smokeFromJars', 'modernizationSmoke', 'networkServerSmoke', 'verifyGradleOnlyPublicSurface',
            'verifyCentralizedReleaseMetadata', 'verifyCentralizedJavaCompileConventions', 'verifyArtifactInventory', 'verifyExtractedOsgiStubProject', 'verifyExtractedCommonsProject', 'verifyExtractedClientProject',
            'verifyExtractedToolsProject', 'verifyExtractedRunnerProject', 'verifyExtractedOptionalToolsProject',
            'verifyExtractedServerProject', 'verifyExtractedEngineProject'
}

tasks.register('test') {
    group = 'verification'
    description = 'Alias for the current smoke verification tasks.'
    dependsOn 'smoke', 'smokeFromJars', 'modernizationSmoke', 'networkServerSmoke'
}

tasks.named('build') {
    dependsOn 'jars', 'check'
}
"""

new = """tasks.named('check') {
    dependsOn 'verifyReleaseArtifacts', 'verifyReleaseDistribution', 'verifyGradleOnlyPublicSurface',
            'verifyCentralizedReleaseMetadata', 'verifyCentralizedJavaCompileConventions', 'verifyArtifactInventory', 'verifyExtractedOsgiStubProject', 'verifyExtractedCommonsProject', 'verifyExtractedClientProject',
            'verifyExtractedToolsProject', 'verifyExtractedRunnerProject', 'verifyExtractedOptionalToolsProject',
            'verifyExtractedServerProject', 'verifyExtractedEngineProject'
}

tasks.register('fullVerification') {
    group = 'verification'
    description = 'Runs the full DelosDB verification suite, including integration smoke checks.'
    dependsOn 'check', 'smoke', 'smokeFromJars', 'modernizationSmoke', 'networkServerSmoke', 'sysinfoFromJars'
}

tasks.register('test') {
    group = 'verification'
    description = 'Alias for the full DelosDB verification suite.'
    dependsOn 'fullVerification'
}

tasks.named('build') {
    dependsOn 'jars', 'check'
}
"""

text = path.read_text()
if old not in text:
    raise SystemExit('ERROR: expected Gradle lifecycle block not found. No changes made.')
path.write_text(text.replace(old, new, 1))
print('Updated build.gradle: integration smoke checks moved from check/build into fullVerification.')
