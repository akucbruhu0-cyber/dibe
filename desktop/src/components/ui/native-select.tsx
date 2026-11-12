import * as React from 'react'
import { cn } from '~/lib/style'

const NativeSelect = React.forwardRef<HTMLSelectElement, React.SelectHTMLAttributes<HTMLSelectElement>>(({ className, ...props }, ref) => (
	<select
		ref={ref}
		className={cn(
			'flex h-11 w-full cursor-pointer rounded-full border border-border bg-card px-3.5 py-2 text-sm ring-offset-card focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-50',
			className,
		)}
		{...props}
	/>
))

NativeSelect.displayName = 'NativeSelect'

export { NativeSelect }
