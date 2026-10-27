import { describe, expect, it } from 'vitest'
import { composeSkill, withFrontmatter, type SkillContext } from './skill'

const context: SkillContext = {
	baseUrl: 'http://127.0.0.1:51234',
	serverSkill: '# Server Local Transcription API\n\nBase URL: http://127.0.0.1:51234\n',
	transcriptsFolder: '/Users/me/Documents/DIBE',
	configPath: '/Users/me/Library/Application Support/vibe/app_config.json',
	dictationShortcut: 'Alt+Space',
	serverBinary: '/Applications/vibe.app/Contents/MacOS/server',
	vibeBinary: '/Applications/vibe.app/Contents/MacOS/vibe',
	serverInfoPath: '/Users/me/Library/Application Support/vibe/server.json',
}

describe('composeSkill', () => {
	// A refactor that drops a section is invisible until an agent is missing half its instructions.
	it('keeps every part an agent needs', () => {
		const skill = composeSkill(context)
		expect(skill).toContain('# Server Local Transcription API')
		expect(skill).toContain(context.baseUrl)
		expect(skill).toContain(context.transcriptsFolder!)
		expect(skill).toContain(context.configPath!)
		expect(skill).toContain('transcript.vibe.json')
		expect(skill).toContain('"dictation.shortcut": "Alt+Space"')
		expect(skill).toContain('# When invoked without a task')
	})

	// The port and the token are per-run: an agent that cannot find the live pair, or the offline route, is stuck.
	it('routes around the dynamic port and token', () => {
		const skill = composeSkill(context)
		expect(skill).toContain(context.serverInfoPath!)
		expect(skill).toContain('Authorization: Bearer <token>')
		expect(skill).toContain('/health')
		expect(skill).toContain(context.serverBinary!)
		expect(skill).toContain(context.vibeBinary!)
	})

	it('still names both transcript layouts when the folder cannot be resolved', () => {
		const skill = composeSkill({ ...context, transcriptsFolder: null, configPath: null, serverBinary: null, vibeBinary: null, serverInfoPath: null })
		expect(skill).toContain('$HOME/Documents/DIBE')
		expect(skill).toContain('`server.json` in the DIBE app data folder')
		expect(skill).toContain('Settings → API & Agents → Config file')
		expect(skill).toContain('bundles the `server` engine')
	})
})

describe('withFrontmatter', () => {
	it('opens with the frontmatter the agent runtimes index by', () => {
		expect(withFrontmatter(composeSkill(context)).split('\n').slice(0, 4)).toEqual(['---', 'name: dibe', expect.stringContaining('description:'), '---'])
	})
})
