import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, it } from 'vitest'

it('updater_has_no_endpoints_in_the_fork', () => {
	const conf = JSON.parse(readFileSync(join(__dirname, '../../src-tauri/tauri.conf.json'), 'utf8'))
	expect(conf.plugins.updater.endpoints).toEqual([])
})
