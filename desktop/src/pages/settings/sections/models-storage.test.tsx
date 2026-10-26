// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { m } from '~/paraglide/messages.js'
import type { ModelsStorage } from '~/lib/storage'
import { ModelsStorageBlock } from './models-storage'

const mocks = vi.hoisted(() => ({ invoke: vi.fn(), ask: vi.fn() }))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))
vi.mock('@tauri-apps/plugin-dialog', () => ({ ask: mocks.ask }))
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))

const MODEL = '/models/ggml-model.bin'
const COPY = '/models/ggml-model_72k5tlmu.bin'
const LEGACY = '/models/diar_streaming_sortformer_4spk-v2.1.onnx'

const full: ModelsStorage = {
	totalBytes: 3_692_000_000,
	reclaimableBytes: 2_092_000_000,
	files: [
		{ path: MODEL, name: 'ggml-model.bin', size: 1_600_000_000, kind: { kind: 'InUse' } },
		{ path: COPY, name: 'ggml-model_72k5tlmu.bin', size: 1_600_000_000, kind: { kind: 'Duplicate', of: 'ggml-model.bin' } },
		{ path: LEGACY, name: 'diar_streaming_sortformer_4spk-v2.1.onnx', size: 492_000_000, kind: { kind: 'Legacy' } },
	],
}
const afterDelete: ModelsStorage = {
	totalBytes: 2_092_000_000,
	reclaimableBytes: 492_000_000,
	files: [full.files[0], full.files[2]],
}

beforeEach(() => {
	mocks.invoke.mockReset()
	mocks.ask.mockReset()
	mocks.invoke.mockImplementation(async (command: string) => {
		if (command === 'get_models_folder') return '/models'
		if (command === 'scan_models_storage') return full
	})
})
afterEach(cleanup)

const deleteCalls = () => mocks.invoke.mock.calls.filter(([command]) => command === 'delete_model_file')

describe('models storage block', () => {
	it('shows the folder size and what can be freed, listing only duplicates and old files', async () => {
		render(<ModelsStorageBlock modelPath={MODEL} />)

		expect(await screen.findByText(m.storageCanFree({ size: '2.1 GB' }))).toBeTruthy()
		expect(screen.getByText('3.7 GB')).toBeTruthy()
		expect(screen.getByText(m.storageDuplicateOf({ name: 'ggml-model.bin' }))).toBeTruthy()
		expect(screen.getByText(m.storageLegacy())).toBeTruthy()
		expect(screen.getAllByRole('button', { name: m.storageDelete() })).toHaveLength(2)
		// The file in use is counted in the total but never offered.
		expect(screen.queryByText('ggml-model.bin')).toBeNull()
		expect(mocks.invoke).toHaveBeenCalledWith('scan_models_storage', {
			keep: [MODEL, '/models/ggml-silero-v6.2.0.bin', '/models/diar_streaming_sortformer_4spk-v2.q8_0.gguf'],
		})
	})

	it('deletes the chosen file only after the confirmation, then scans again', async () => {
		mocks.ask.mockResolvedValue(true)
		render(<ModelsStorageBlock modelPath={MODEL} />)
		const [deleteDuplicate] = await screen.findAllByRole('button', { name: m.storageDelete() })
		mocks.invoke.mockImplementation(async (command: string) => {
			if (command === 'get_models_folder') return '/models'
			if (command === 'scan_models_storage') return afterDelete
		})

		fireEvent.click(deleteDuplicate)

		await waitFor(() => expect(deleteCalls()).toHaveLength(1))
		expect(mocks.ask).toHaveBeenCalledWith(
			m.storageDeleteBody({ name: 'ggml-model_72k5tlmu.bin', size: '1.6 GB' }),
			expect.objectContaining({ kind: 'warning' }),
		)
		expect(deleteCalls()).toEqual([
			['delete_model_file', { path: COPY, keep: [MODEL, '/models/ggml-silero-v6.2.0.bin', '/models/diar_streaming_sortformer_4spk-v2.q8_0.gguf'] }],
		])
		await waitFor(() => expect(screen.getAllByRole('button', { name: m.storageDelete() })).toHaveLength(1))
		expect(screen.getByText(m.storageCanFree({ size: '492 MB' }))).toBeTruthy()
	})

	it('does nothing when the confirmation is declined', async () => {
		mocks.ask.mockResolvedValue(false)
		render(<ModelsStorageBlock modelPath={MODEL} />)

		fireEvent.click((await screen.findAllByRole('button', { name: m.storageDelete() }))[0])

		await waitFor(() => expect(mocks.ask).toHaveBeenCalled())
		await new Promise((resolve) => setTimeout(resolve))
		expect(deleteCalls()).toHaveLength(0)
	})
})
