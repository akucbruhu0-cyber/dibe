import { ask } from '@tauri-apps/plugin-dialog'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '~/components/ui/badge'
import { Button } from '~/components/ui/button'
import { deleteModelFile, formatBytes, scanModelsStorage, type ModelFile, type ModelsStorage } from '~/lib/storage'
import { m } from '~/paraglide/messages.js'
import { SettingsGroup, SettingsRow } from './shared'

function isReclaimable(file: ModelFile) {
	return file.kind.kind === 'Duplicate' || file.kind.kind === 'Legacy'
}

/**
 * How much disk the models folder uses, and the copies and leftovers in it that can go. Only those
 * are listed; the backend refuses to delete anything else, in use or not.
 */
export function ModelsStorageBlock({ modelPath }: { modelPath: string | null }) {
	const [storage, setStorage] = useState<ModelsStorage | null>(null)
	const [failed, setFailed] = useState(false)
	const [deleting, setDeleting] = useState(false)
	const [rescan, setRescan] = useState(0)

	// Hashing duplicates takes a moment, so this runs when the section opens or the selection changes,
	// not on every window focus.
	useEffect(() => {
		let stale = false
		scanModelsStorage(modelPath)
			.then((result) => {
				if (stale) return
				setStorage(result)
				setFailed(false)
			})
			.catch((error) => {
				console.error('failed to scan the models folder:', error)
				if (!stale) setFailed(true)
			})
		return () => {
			stale = true
		}
	}, [modelPath, rescan])

	async function remove(file: ModelFile) {
		const confirmed = await ask(m.storageDeleteBody({ name: file.name, size: formatBytes(file.size) }), {
			title: m.storageDeleteTitle(),
			kind: 'warning',
			okLabel: m.delete(),
			cancelLabel: m.cancel(),
		})
		if (!confirmed) return
		setDeleting(true)
		try {
			await deleteModelFile(file.path, modelPath)
		} catch (error) {
			console.error('failed to delete a model file:', error)
			toast.error(m.storageDeleteFailed({ name: file.name }))
		} finally {
			setDeleting(false)
			setRescan((count) => count + 1)
		}
	}

	const reclaimable = storage?.files.filter(isReclaimable) ?? []
	const summary = failed
		? m.storageScanFailed()
		: !storage
			? m.storageScanning()
			: storage.reclaimableBytes > 0
				? m.storageCanFree({ size: formatBytes(storage.reclaimableBytes) })
				: m.storageNothingToFree()

	return (
		<SettingsGroup title={m.storage()}>
			<SettingsRow label={m.storageUsed()} description={summary}>
				{storage && <span className="text-sm tabular-nums text-muted-foreground">{formatBytes(storage.totalBytes)}</span>}
			</SettingsRow>
			{reclaimable.map((file) => (
				<div key={file.path} className="flex items-center gap-3 px-4 py-3">
					<div className="min-w-0 flex-1 space-y-1">
						<p dir="auto" className="truncate text-sm">
							{file.name}
						</p>
						<div className="flex items-center gap-2 text-xs text-muted-foreground">
							<Badge variant="secondary" dir="auto" className="min-w-0 font-normal">
								<span className="truncate">
									{file.kind.kind === 'Duplicate' ? m.storageDuplicateOf({ name: file.kind.of }) : m.storageLegacy()}
								</span>
							</Badge>
							<span className="shrink-0 tabular-nums">{formatBytes(file.size)}</span>
						</div>
					</div>
					<Button size="sm" variant="outline" className="shrink-0" disabled={deleting} onClick={() => remove(file)}>
						{m.storageDelete()}
					</Button>
				</div>
			))}
		</SettingsGroup>
	)
}
