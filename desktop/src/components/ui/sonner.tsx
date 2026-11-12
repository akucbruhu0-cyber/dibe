import { Toaster as Sonner } from 'sonner'
import { getTextDirection } from '~/paraglide/runtime.js'

type ToasterProps = React.ComponentProps<typeof Sonner>

const Toaster = ({ ...props }: ToasterProps) => {
	return (
		<Sonner
			dir={getTextDirection()}
			theme="system"
			className="toaster group"
			// Sonner's own (unlayered) CSS paints the toast from these variables, so the glass goes in here.
			style={
				{
					'--normal-bg': 'var(--glass)',
					'--normal-border': 'var(--glass-border)',
					'--normal-text': 'var(--foreground)',
					'--border-radius': '12px',
				} as React.CSSProperties
			}
			toastOptions={{
				classNames: {
					toast: 'group toast glass rounded-[12px] border text-card-foreground',
					title: 'text-sm font-semibold',
					description: 'text-sm text-muted-foreground',
					actionButton: 'rounded-full! bg-primary! text-primary-foreground! hover:bg-primary/85!',
					cancelButton: 'rounded-full! bg-muted! text-foreground! hover:bg-accent!',
					success: 'border-success/30 text-foreground',
					error: 'border-destructive/35 text-foreground',
					warning: 'border-chart-4/40 text-foreground',
					info: 'border-primary-strong/30 text-foreground',
				},
			}}
			{...props}
		/>
	)
}

export { Toaster }
