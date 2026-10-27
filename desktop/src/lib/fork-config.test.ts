import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, it } from 'vitest'

const conf = JSON.parse(readFileSync(join(__dirname, '../../src-tauri/tauri.conf.json'), 'utf8'))

it('updater_has_no_endpoints_in_the_fork', () => {
	expect(conf.plugins.updater.endpoints).toEqual([])
})

it('fork_builds_no_updater_artifacts', () => {
	// Nothing serves them, and signing them needs the official key: a local build would fail.
	expect(conf.bundle.createUpdaterArtifacts).toBe(false)
})
