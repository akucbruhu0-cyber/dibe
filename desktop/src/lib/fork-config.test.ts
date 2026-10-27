import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, it } from 'vitest'

const conf = JSON.parse(readFileSync(join(__dirname, '../../src-tauri/tauri.conf.json'), 'utf8'))
const mainCapability = JSON.parse(readFileSync(join(__dirname, '../../src-tauri/capabilities/main.json'), 'utf8'))

it('main_window_can_be_dragged', () => {
	// The title bar is an overlay, so `data-tauri-drag-region` is the only way to move the window,
	// and it calls window.startDragging — which `core:window:default` does not allow.
	expect(mainCapability.permissions).toContain('core:window:allow-start-dragging')
})

it('updater_has_no_endpoints_in_the_fork', () => {
	expect(conf.plugins.updater.endpoints).toEqual([])
})

it('fork_builds_no_updater_artifacts', () => {
	// Nothing serves them, and signing them needs the official key: a local build would fail.
	expect(conf.bundle.createUpdaterArtifacts).toBe(false)
})
