import type { DrawnVisual } from './docx'
import { LIGHT_INK, visualToSvg } from './visual-svg'
import type { Visual } from './visuals'

/** A visual's SVG rasterized at 2x through a canvas, for files that take pictures (Word). Needs a browser. */
export async function visualToPng(visual: Visual): Promise<DrawnVisual> {
	const { svg, width, height } = visualToSvg(visual, LIGHT_INK)
	const image = new Image()
	image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
	await image.decode()
	const canvas = document.createElement('canvas')
	canvas.width = width * 2
	canvas.height = height * 2
	canvas.getContext('2d')!.drawImage(image, 0, 0, canvas.width, canvas.height)
	const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
	if (!blob) throw new Error('Could not draw the chart')
	return { png: new Uint8Array(await blob.arrayBuffer()), width, height }
}
