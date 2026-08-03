import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ConfidencePill, SeverityBadge, StatusBadge } from './StatusBadge'

describe('StatusBadge', () => {
  it('renders a human-readable label for each status', () => {
    render(<StatusBadge status="PENDING_REVIEW" />)
    expect(screen.getByText('PENDING REVIEW')).toBeInTheDocument()
  })

  it('applies the approved style for APPROVED', () => {
    render(<StatusBadge status="APPROVED" />)
    expect(screen.getByText('APPROVED')).toHaveClass('badge-approved')
  })
})

describe('SeverityBadge', () => {
  it('renders nothing when severity is null', () => {
    const { container } = render(<SeverityBadge severity={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders the severity text when present', () => {
    render(<SeverityBadge severity="critical" />)
    expect(screen.getByText('critical')).toBeInTheDocument()
  })
})

describe('ConfidencePill', () => {
  it('shows a dash when confidence is null', () => {
    render(<ConfidencePill confidence={null} />)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('classifies >= 0.9 as high confidence', () => {
    render(<ConfidencePill confidence={0.95} />)
    const pill = screen.getByText('95%')
    expect(pill).toHaveClass('confidence-high')
  })

  it('classifies between 0.6 and 0.9 as medium confidence', () => {
    render(<ConfidencePill confidence={0.75} />)
    expect(screen.getByText('75%')).toHaveClass('confidence-medium')
  })

  it('classifies below 0.6 as low confidence', () => {
    render(<ConfidencePill confidence={0.3} />)
    expect(screen.getByText('30%')).toHaveClass('confidence-low')
  })
})
