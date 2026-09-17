#!/usr/bin/env python3
"""Deterministic, dependency-free SVG chart primitives for the benchmark report.

Every function returns a self-contained ``<svg>`` string with no scripts, no
external references and stable numeric formatting, so generated charts can be
committed and verified byte-for-byte by ``report.py check``.
"""
import math

# Fixed identities so the same series always gets the same colour across charts.
CHOLESKY = '#1f77b4'
CG = '#d62728'
FAMILY_COLORS = {
    'well': '#1f77b4', 'wide': '#ff7f0e', 'dependent': '#d62728',
    'degenerate': '#9467bd', 'dense': '#2ca02c', 'planted': '#8c564b',
    'structured': '#17becf',
}
GRID = '#dddddd'
AXIS = '#555555'
TEXT = '#222222'
MUTED = '#888888'
FONT = ('font-family="-apple-system,BlinkMacSystemFont,Segoe UI,Helvetica,'
        'Arial,sans-serif"')


def _n(value):
    """Format a coordinate with stable, minimal precision."""
    if value is None or not math.isfinite(value):
        return '0'
    rounded = round(value, 2)
    if rounded == int(rounded):
        return str(int(rounded))
    return f'{rounded:.2f}'


def _esc(text):
    return (str(text).replace('&', '&amp;').replace('<', '&lt;')
            .replace('>', '&gt;').replace('"', '&quot;'))


def _g(value):
    """Human axis label: compact scientific for large/small magnitudes."""
    if value == 0:
        return '0'
    magnitude = abs(value)
    if magnitude >= 1000 or magnitude < 0.01:
        exponent = int(math.floor(math.log10(magnitude)))
        mantissa = value / (10 ** exponent)
        if abs(mantissa - round(mantissa)) < 1e-9:
            mantissa = round(mantissa)
            return (f'{mantissa:g}e{exponent}' if mantissa != 1 else f'1e{exponent}')
        return f'{value:.3g}'
    return f'{value:g}'


class Axis:
    """Maps data values to pixels, optionally on a base-10 log scale."""

    def __init__(self, low, high, pixel_low, pixel_high, log=False):
        self.log = log and low > 0 and high > 0
        if self.log:
            low, high = math.log10(low), math.log10(high)
        if low == high:
            low, high = low - 1, high + 1
        self.low, self.high = low, high
        self.pixel_low, self.pixel_high = pixel_low, pixel_high

    def scale(self, value):
        if value is None:
            return None
        if self.log:
            if value <= 0:
                return None
            value = math.log10(value)
        frac = (value - self.low) / (self.high - self.low)
        return self.pixel_low + frac * (self.pixel_high - self.pixel_low)

    def ticks(self, count=5):
        if self.log:
            lo, hi = int(math.floor(self.low)), int(math.ceil(self.high))
            return [(10 ** e, 10 ** e) for e in range(lo, hi + 1)]
        span = self.high - self.low
        if span <= 0:
            return [(self.low, self.low)]
        raw = span / count
        magnitude = 10 ** math.floor(math.log10(raw))
        norm = raw / magnitude
        nice = 1 if norm < 1.5 else 2 if norm < 3 else 5 if norm < 7 else 10
        step = nice * magnitude
        first = math.ceil(self.low / step - 1e-9)
        last = math.floor(self.high / step + 1e-9)
        return [(i * step, i * step) for i in range(first, last + 1)]


def _header(width, height, title, subtitle=''):
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" '
             f'height="{height}" viewBox="0 0 {width} {height}" {FONT} '
             f'font-size="12" role="img" aria-label="{_esc(title)}">',
             f'<rect width="{width}" height="{height}" fill="#ffffff"/>']
    if title:
        parts.append(f'<text x="16" y="22" font-size="15" font-weight="600" '
                     f'fill="{TEXT}">{_esc(title)}</text>')
    if subtitle:
        parts.append(f'<text x="16" y="40" font-size="11.5" fill="{MUTED}">'
                     f'{_esc(subtitle)}</text>')
    return parts


def _frame(parts, left, right, top, bottom, x_axis, y_axis, x_label, y_label,
           x_fmt=_g, y_fmt=_g, x_ticks=None):
    for _, value in y_axis.ticks():
        py = y_axis.scale(value)
        if py is None or py < top - 0.5 or py > bottom + 0.5:
            continue
        parts.append(f'<line x1="{left}" y1="{_n(py)}" x2="{right}" '
                     f'y2="{_n(py)}" stroke="{GRID}"/>')
        parts.append(f'<text x="{left - 8}" y="{_n(py + 4)}" text-anchor="end" '
                     f'fill="{MUTED}" font-size="10.5">{_esc(y_fmt(value))}</text>')
    tick_values = [(v, v) for v in x_ticks] if x_ticks is not None else x_axis.ticks()
    for _, value in tick_values:
        px = x_axis.scale(value)
        if px is None or px < left - 0.5 or px > right + 0.5:
            continue
        parts.append(f'<line x1="{_n(px)}" y1="{top}" x2="{_n(px)}" '
                     f'y2="{bottom}" stroke="{GRID}"/>')
        parts.append(f'<text x="{_n(px)}" y="{bottom + 16}" text-anchor="middle" '
                     f'fill="{MUTED}" font-size="10.5">{_esc(x_fmt(value))}</text>')
    parts.append(f'<line x1="{left}" y1="{top}" x2="{left}" y2="{bottom}" '
                 f'stroke="{AXIS}"/>')
    parts.append(f'<line x1="{left}" y1="{bottom}" x2="{right}" y2="{bottom}" '
                 f'stroke="{AXIS}"/>')
    if x_label:
        parts.append(f'<text x="{(left + right) / 2:.0f}" y="{bottom + 34}" '
                     f'text-anchor="middle" fill="{TEXT}" font-size="11.5">'
                     f'{_esc(x_label)}</text>')
    if y_label:
        cy = (top + bottom) / 2
        parts.append(f'<text x="16" y="{cy:.0f}" text-anchor="middle" '
                     f'fill="{TEXT}" font-size="11.5" transform="rotate(-90 16 '
                     f'{cy:.0f})">{_esc(y_label)}</text>')


def _legend(parts, entries, x, y):
    for name, color in entries:
        parts.append(f'<rect x="{_n(x)}" y="{_n(y - 8)}" width="11" height="11" '
                     f'rx="2" fill="{color}"/>')
        parts.append(f'<text x="{_n(x + 16)}" y="{_n(y + 1)}" fill="{TEXT}" '
                     f'font-size="11">{_esc(name)}</text>')
        x += 20 + 7.2 * len(str(name))


def line_chart(series, title, x_label, y_label, subtitle='', x_log=True,
               y_log=True, width=680, height=360, x_fmt=_g, y_fmt=_g, x_ticks=None):
    """series: list of dict(name, color, points=[(x, y), ...], dashed=bool)."""
    left, right, top, bottom = 64, width - 20, 66, height - 54
    xs = [x for s in series for x, _ in s['points'] if x is not None]
    ys = [y for s in series for _, y in s['points'] if y is not None]
    parts = _header(width, height, title, subtitle)
    if not xs or not ys:
        parts.append(f'<text x="{width / 2:.0f}" y="{height / 2:.0f}" '
                     f'text-anchor="middle" fill="{MUTED}">no data</text></svg>')
        return '\n'.join(parts)
    x_axis = Axis(min(xs), max(xs), left, right, log=x_log)
    y_axis = Axis(min(ys), max(ys), bottom, top, log=y_log)
    _frame(parts, left, right, top, bottom, x_axis, y_axis, x_label, y_label,
           x_fmt, y_fmt, x_ticks)
    for s in series:
        pts = [(x_axis.scale(x), y_axis.scale(y)) for x, y in s['points']
               if x is not None and y is not None]
        pts = [(px, py) for px, py in pts if px is not None and py is not None]
        if not pts:
            continue
        dash = ' stroke-dasharray="5,4"' if s.get('dashed') else ''
        path = ' '.join(f'{"M" if i == 0 else "L"}{_n(px)},{_n(py)}'
                        for i, (px, py) in enumerate(pts))
        parts.append(f'<path d="{path}" fill="none" stroke="{s["color"]}" '
                     f'stroke-width="2"{dash}/>')
        for px, py in pts:
            parts.append(f'<circle cx="{_n(px)}" cy="{_n(py)}" r="2.6" '
                         f'fill="{s["color"]}"/>')
    _legend(parts, [(s["name"], s["color"]) for s in series], left, top - 12)
    parts.append('</svg>')
    return '\n'.join(parts)


def step_chart(series, title, x_label, y_label, subtitle='', x_log=True,
               width=680, height=360):
    """Cumulative step curves (e.g. Dolan-More performance profile).

    series: list of dict(name, color, points=[(x, y), ...]) already sorted by x;
    y in [0, 1]. Drawn as right-continuous steps.
    """
    left, right, top, bottom = 64, width - 20, 66, height - 54
    xs = [x for s in series for x, _ in s['points'] if x is not None]
    parts = _header(width, height, title, subtitle)
    if not xs:
        parts.append(f'<text x="{width / 2:.0f}" y="{height / 2:.0f}" '
                     f'text-anchor="middle" fill="{MUTED}">no data</text></svg>')
        return '\n'.join(parts)
    x_axis = Axis(min(xs), max(xs), left, right, log=x_log)
    y_axis = Axis(0, 1, bottom, top, log=False)
    _frame(parts, left, right, top, bottom, x_axis, y_axis, x_label, y_label,
           x_fmt=_g, y_fmt=lambda v: f'{v:.0%}')
    for s in series:
        d, prev_y = [], None
        for x, y in s['points']:
            px, py = x_axis.scale(x), y_axis.scale(y)
            if px is None or py is None:
                continue
            if prev_y is None:
                d.append(f'M{_n(px)},{_n(py)}')
            else:
                d.append(f'L{_n(px)},{_n(prev_y)} L{_n(px)},{_n(py)}')
            prev_y = py
        if prev_y is not None:
            d.append(f'L{_n(right)},{_n(prev_y)}')
        if d:
            parts.append(f'<path d="{" ".join(d)}" fill="none" '
                         f'stroke="{s["color"]}" stroke-width="2"/>')
    _legend(parts, [(s["name"], s["color"]) for s in series], left, top - 12)
    parts.append('</svg>')
    return '\n'.join(parts)


def bar_chart(bars, title, x_label, y_label, subtitle='', baseline=None,
              y_log=False, width=680, height=360, rotate=True):
    """bars: list of dict(label, value, color). Optional horizontal baseline."""
    left, right, top, bottom = 64, width - 20, 66, height - (96 if rotate else 54)
    values = [b['value'] for b in bars if b['value'] is not None]
    parts = _header(width, height, title, subtitle)
    if not values:
        parts.append(f'<text x="{width / 2:.0f}" y="{height / 2:.0f}" '
                     f'text-anchor="middle" fill="{MUTED}">no data</text></svg>')
        return '\n'.join(parts)
    low = min(values + ([baseline] if baseline is not None else []))
    high = max(values + ([baseline] if baseline is not None else []))
    low = min(low, 0) if not y_log else max(min(values) / 2, 1e-9)
    y_axis = Axis(low, high * 1.08, bottom, top, log=y_log)
    for _, value in y_axis.ticks():
        py = y_axis.scale(value)
        if py is None or py < top - 0.5 or py > bottom + 0.5:
            continue
        parts.append(f'<line x1="{left}" y1="{_n(py)}" x2="{right}" '
                     f'y2="{_n(py)}" stroke="{GRID}"/>')
        parts.append(f'<text x="{left - 8}" y="{_n(py + 4)}" text-anchor="end" '
                     f'fill="{MUTED}" font-size="10.5">{_esc(_g(value))}</text>')
    slot = (right - left) / len(bars)
    bar_w = min(slot * 0.7, 46)
    zero = y_axis.scale(max(low, 0) if not y_log else low)
    for i, b in enumerate(bars):
        cx = left + slot * (i + 0.5)
        if b['value'] is None:
            continue
        py = y_axis.scale(b['value'])
        y0, y1 = min(py, zero), max(py, zero)
        parts.append(f'<rect x="{_n(cx - bar_w / 2)}" y="{_n(y0)}" '
                     f'width="{_n(bar_w)}" height="{_n(y1 - y0)}" rx="2" '
                     f'fill="{b["color"]}"/>')
        anchor, ty = ('middle', 'transform="rotate(-40 %s %s)"' % (_n(cx), _n(bottom + 14))) if rotate else ('middle', '')
        parts.append(f'<text x="{_n(cx)}" y="{_n(bottom + 14)}" '
                     f'text-anchor="{"end" if rotate else "middle"}" '
                     f'fill="{MUTED}" font-size="9.5" {ty}>{_esc(b["label"])}</text>')
    if baseline is not None:
        py = y_axis.scale(baseline)
        if py is not None:
            parts.append(f'<line x1="{left}" y1="{_n(py)}" x2="{right}" '
                         f'y2="{_n(py)}" stroke="{AXIS}" stroke-width="1.4" '
                         f'stroke-dasharray="6,4"/>')
    parts.append(f'<line x1="{left}" y1="{top}" x2="{left}" y2="{bottom}" '
                 f'stroke="{AXIS}"/>')
    parts.append(f'<line x1="{left}" y1="{_n(zero)}" x2="{right}" '
                 f'y2="{_n(zero)}" stroke="{AXIS}"/>')
    if y_label:
        cy = (top + bottom) / 2
        parts.append(f'<text x="16" y="{cy:.0f}" text-anchor="middle" '
                     f'fill="{TEXT}" font-size="11.5" transform="rotate(-90 16 '
                     f'{cy:.0f})">{_esc(y_label)}</text>')
    parts.append('</svg>')
    return '\n'.join(parts)


def scatter_chart(series, title, x_label, y_label, subtitle='', x_log=True,
                  y_log=True, width=680, height=360):
    """series: list of dict(name, color, points=[(x, y), ...])."""
    left, right, top, bottom = 64, width - 20, 66, height - 54
    xs = [x for s in series for x, _ in s['points'] if x is not None]
    ys = [y for s in series for _, y in s['points'] if y is not None]
    parts = _header(width, height, title, subtitle)
    if not xs or not ys:
        parts.append(f'<text x="{width / 2:.0f}" y="{height / 2:.0f}" '
                     f'text-anchor="middle" fill="{MUTED}">no data</text></svg>')
        return '\n'.join(parts)
    x_axis = Axis(min(xs), max(xs), left, right, log=x_log)
    y_axis = Axis(min(ys), max(ys), bottom, top, log=y_log)
    _frame(parts, left, right, top, bottom, x_axis, y_axis, x_label, y_label)
    for s in series:
        for x, y in s['points']:
            px, py = x_axis.scale(x), y_axis.scale(y)
            if px is None or py is None:
                continue
            parts.append(f'<circle cx="{_n(px)}" cy="{_n(py)}" r="3.4" '
                         f'fill="{s["color"]}" fill-opacity="0.75"/>')
    _legend(parts, [(s["name"], s["color"]) for s in series], left, top - 12)
    parts.append('</svg>')
    return '\n'.join(parts)
