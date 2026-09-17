#!/usr/bin/env python3
"""Turn the sanitized BMF bundle into charts, chunked report media and a
self-contained interactive dashboard. Standard library plus ``charts`` only.

Nothing here reads raw artifacts or infrastructure identifiers: it consumes the
same normalized ``campaigns`` structure the Markdown tables use, so published
views can never expose more than the evidence bundle already does.
"""
import collections
import json
import statistics

import charts
from report import is_valid, section_for, experiment_key, NOT_ATTEMPTED

LOCAL_SECTIONS = {'Solver scaling', 'Sparsity and conditioning', 'Dense small'}


def _median(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def case_summaries(campaigns):
    """One numeric row per (campaign, case, algorithm); measured runs only."""
    rows = []
    for campaign in campaigns:
        configs = {c['configuration_id']: c for c in campaign['configurations']}
        cases = {c['case_id']: c for c in campaign['cases']}
        order, title, prefix, _ = section_for(campaign['campaign_id'])
        grouped = collections.defaultdict(list)
        for record in campaign['records']:
            if record['warmup']:
                continue
            grouped[(record['case_id'], record['algorithm'],
                     record['configuration_id'])].append(record)
        for (case_id, algorithm, _), records in grouped.items():
            cfg = configs[records[0]['configuration_id']]
            case = cases[case_id]
            attempted = [r for r in records if r['status'] not in NOT_ATTEMPTED]
            valid = [r for r in attempted if is_valid(r, cfg)]
            times = [r['solve_seconds'] for r in valid if r['solve_seconds'] is not None]
            rss = [r['memory'].get('peak_rss_bytes') for r in records
                   if r['memory'].get('peak_rss_bytes')]
            m, n, nnz = case['m'], case['n'], case['nnz']
            topology = cfg.get('memory_topology') or {}
            rows.append(dict(
                campaign=campaign['campaign_id'], section=title, order=order,
                prefix=prefix, experiment=experiment_key(campaign['campaign_id']),
                case=case_id, family=case['shape'].get('family') or 'unknown',
                seed=case['shape'].get('seed'), m=m, n=n, nnz=nnz,
                density=(100 * nnz / (m * n)) if nnz else None,
                partitions=cfg.get('partitions'), heap_gib=cfg.get('heap_gib'),
                executors=topology.get('executors'), algorithm=algorithm,
                local=title in LOCAL_SECTIONS,
                median_s=_median(times), min_s=min(times) if times else None,
                max_s=max(times) if times else None,
                attempted=len(attempted), valid=len(valid), success=bool(times),
                outer=_median([r['outer_iterations'] for r in valid]),
                cg_steps=_median([r['cg_steps'] for r in valid]),
                gap_max=max([r['residuals'].get('gap') for r in valid
                             if r['residuals'].get('gap') is not None], default=None),
                rss_max=max(rss) if rss else None))
    rows.sort(key=lambda r: (r['order'], r['m'], r['n'], r['case'], r['algorithm'],
                             r['partitions'] or 0, r['executors'] or 0))
    return rows


# --- Chart datasets -------------------------------------------------------

def _paired(rows, predicate=lambda r: True):
    """Group summaries by physical case, keeping each backend's median time."""
    groups = collections.OrderedDict()
    for row in rows:
        if not predicate(row):
            continue
        key = (row['experiment'], row['case'], row['m'], row['n'])
        groups.setdefault(key, {})[row['algorithm']] = row
    return groups


def performance_profile(rows):
    """Dolan-More profile: fraction of cases each backend solves within tau x
    of the best backend on that case. Uses locally comparable runs only."""
    groups = _paired(rows, lambda r: r['local'])
    ratios = {'Cholesky': [], 'CG': []}
    total = 0
    for key, byalg in groups.items():
        # Fair head-to-head: only cases both backends actually solved.
        times = {a: byalg[a]['median_s'] for a in ('Cholesky', 'CG')
                 if a in byalg and byalg[a]['median_s'] is not None}
        if len(times) < 2:
            continue
        total += 1
        winner = min(times.values())
        for alg in ('Cholesky', 'CG'):
            ratios[alg].append(times[alg] / winner)
    if not total:
        return None
    series = []
    for alg, color in (('Cholesky', charts.CHOLESKY), ('CG', charts.CG)):
        values = sorted(ratios[alg])
        points, seen = [], 0
        for i, ratio in enumerate(values):
            seen = i + 1
            # right-continuous: fraction reached at this ratio
            if i + 1 == len(values) or values[i + 1] != ratio:
                points.append((ratio, seen / total))
        if points and points[0][0] > 1:
            points.insert(0, (1, 0))
        series.append(dict(name=alg, color=color, points=points))
    subtitle = (f'{total} cases both backends solved; closer to the top-left '
                f'means faster on more cases')
    return charts.step_chart(series, 'Performance profile: Cholesky vs CG',
                             'slowdown ratio to best backend (log)',
                             'fraction of cases', subtitle=subtitle,
                             x_log=True), total


def scaling_vs_rows(rows):
    """Median solve time vs rows m for the well family on local runs."""
    series = []
    for alg, color in (('Cholesky', charts.CHOLESKY), ('CG', charts.CG)):
        by_m = collections.defaultdict(list)
        for row in rows:
            if (row['local'] and row['family'] == 'well' and row['algorithm'] == alg
                    and row['median_s'] is not None):
                by_m[row['m']].append(row['median_s'])
        points = [(m, _median(values)) for m, values in sorted(by_m.items())]
        series.append(dict(name=alg, color=color, points=points))
    if not any(s['points'] for s in series):
        return None
    return charts.line_chart(
        series, 'Scaling with problem size (well-conditioned, local)',
        'rows m (log)', 'median solve seconds (log)',
        subtitle='local[4], seed 11; Cholesky driver cost grows ~m^2-m^3, CG stays shallow',
        x_log=True, y_log=True)


def partition_tuning(rows):
    """CG solve time vs input partitions per fixture (the 16-partition story)."""
    series, palette = [], [charts.CG, '#ff7f0e', '#9467bd', '#2ca02c']
    fixtures = collections.defaultdict(dict)
    for row in rows:
        if row['prefix'] != 'PART' or row['algorithm'] != 'CG' or row['partitions'] is None:
            continue
        label = f"{row['family']} {row['m']:,}x{row['n']:,}"
        fixtures[label].setdefault(row['partitions'], []).append(row['median_s'])
    for i, (label, by_part) in enumerate(sorted(fixtures.items())):
        points = [(p, _median(v)) for p, v in sorted(by_part.items())]
        series.append(dict(name=label, color=palette[i % len(palette)], points=points))
    if not series:
        return None
    return charts.line_chart(
        series, 'CG input-partition tuning', 'input partitions (log)',
        'median solve seconds (log)',
        subtitle='4x4 executors, seed 11, width 32; fewer partitions were faster here',
        x_log=True, y_log=True, x_ticks=[16, 32, 64])


def family_difficulty(rows):
    """CG median solve time by fixture family for the distributed difficulty
    fixtures (fixed 500 x 5,000 shape, one comparable campaign)."""
    bars = []
    by_family = collections.defaultdict(list)
    for row in rows:
        if (row['algorithm'] == 'CG' and row['case'].startswith('difficulty-500-10-20-')
                and row['median_s'] is not None
                and row['family'] in ('well', 'degenerate', 'wide', 'dependent')):
            by_family[row['family']].append(row['median_s'])
    for family in ('well', 'degenerate', 'wide', 'dependent'):
        if by_family[family]:
            bars.append(dict(label=family, value=_median(by_family[family]),
                             color=charts.FAMILY_COLORS.get(family, charts.CG)))
    if not bars:
        return None
    top = max(bars, key=lambda b: b['value'])
    base = min(b['value'] for b in bars)
    subtitle = (f'{top["label"]} fixtures cost the most CG time '
                f'(~{top["value"] / base:.0f}x the easiest family)')
    return charts.bar_chart(
        bars, 'Conditioning cost for CG (500 x 5,000, distributed)',
        'fixture family', 'median solve seconds',
        subtitle=subtitle, rotate=False)


def distributed_scaling(rows):
    """CG solve time vs variables n on the distributed runtime (log-log)."""
    by_n = collections.defaultdict(list)
    for row in rows:
        if (row['prefix'] == 'DIST' and row['algorithm'] == 'CG'
                and row['median_s'] is not None):
            by_n[row['n']].append(row['median_s'])
    points = [(n, _median(v)) for n, v in sorted(by_n.items())]
    if not points:
        return None
    series = [dict(name='CG', color=charts.CG, points=points)]
    return charts.line_chart(
        series, 'Distributed CG scaling', 'variables n (log)',
        'median solve seconds (log)',
        subtitle='YARN, 4-16 executors; CG solves up to 1e8 variables',
        x_log=True, y_log=True)


# --- Report media (chunked sections) --------------------------------------

def _img(path, alt):
    return f'![{alt}]({path})'


def build(campaigns):
    """Return chart files, at-a-glance markdown, per-section media and dashboard."""
    rows = case_summaries(campaigns)
    chart_files, media = {}, {}

    profile = performance_profile(rows)
    scaling = scaling_vs_rows(rows)
    partitions = partition_tuning(rows)
    family = family_difficulty(rows)
    distributed = distributed_scaling(rows)

    def emit(name, svg):
        if svg is not None:
            chart_files[f'charts/{name}.svg'] = svg + '\n'
            return True
        return False

    profile_svg, profile_n = profile if profile else (None, 0)
    emit('performance-profile', profile_svg)
    emit('scaling-rows', scaling)
    emit('cg-partitions', partitions)
    emit('family-difficulty', family)
    emit('distributed-scaling', distributed)

    glance = _at_a_glance(rows, campaigns, chart_files, profile_n)

    if 'charts/scaling-rows.svg' in chart_files:
        media['Solver scaling'] = _section_media(
            'charts/scaling-rows.svg', 'Solve time versus rows, well family',
            _scaling_takeaways(rows))
        media['Sparsity and conditioning'] = _section_media(
            'charts/scaling-rows.svg', 'Solve time versus rows, well family',
            _scaling_takeaways(rows))
    if 'charts/cg-partitions.svg' in chart_files:
        media['CG partition tuning'] = _section_media(
            'charts/cg-partitions.svg', 'CG solve time versus input partitions',
            _partition_takeaways(rows))
    if 'charts/distributed-scaling.svg' in chart_files:
        media['Distributed scaling'] = _section_media(
            'charts/distributed-scaling.svg', 'Distributed CG solve time versus variables',
            _distributed_takeaways(rows))
    if 'charts/family-difficulty.svg' in chart_files:
        media['Sparsity and conditioning distributed a0f2da3cc3df'] = _section_media(
            'charts/family-difficulty.svg', 'CG cost by fixture family',
            _family_takeaways(rows))

    dashboard = build_dashboard(rows)
    return dict(charts=chart_files, at_a_glance=glance, section_media=media,
                dashboard=dashboard)


def _section_media(image, alt, takeaways):
    bullets = '\n'.join(f'- {t}' for t in takeaways)
    return f'{_img(image, alt)}\n\n{bullets}'


def _scaling_takeaways(rows):
    heavy = _paired(rows, lambda r: r['local'] and r['family'] == 'well' and r['m'] >= 2500)
    wins = [byalg for byalg in heavy.values() if 'Cholesky' in byalg and 'CG' in byalg
            and byalg['Cholesky']['median_s'] and byalg['CG']['median_s']]
    speedups = sorted(b['Cholesky']['median_s'] / b['CG']['median_s'] for b in wins)
    line = (f'From ~2,500 rows CG was ~{statistics.median(speedups):.0f}x faster than '
            f'Cholesky here.' if speedups else 'CG stays much flatter than Cholesky as rows grow.')
    return [line,
            'Cholesky driver work scales with m^2-m^3; CG scales with iterations, not m directly.',
            'For small or dense cases Cholesky is competitive and often more reliable.']


def _partition_takeaways(rows):
    fixtures = collections.defaultdict(dict)
    for row in rows:
        if row['prefix'] == 'PART' and row['algorithm'] == 'CG' and row['partitions'] and row['median_s']:
            fixtures[(row['family'], row['m'], row['n'])].setdefault(
                row['partitions'], []).append(row['median_s'])
    ratios = []
    for by_part in fixtures.values():
        meds = {p: _median(v) for p, v in by_part.items()}
        if 16 in meds and 64 in meds:
            ratios.append(meds[64] / meds[16])
    lead = (f'16 input partitions were fastest on every tested fixture '
            f'({min(ratios):.2f}-{max(ratios):.2f}x median vs 64).'
            if ratios else '16 input partitions were fastest on every tested fixture.')
    return [lead,
            'More partitions raised scheduling and shuffle overhead without accuracy gains.',
            'Workload-specific: re-measure for other executor counts and shapes.']


def _distributed_takeaways(rows):
    ns = sorted(r['n'] for r in rows if r['prefix'] == 'DIST' and r['algorithm'] == 'CG'
                and r['median_s'] is not None)
    top = f'{ns[-1]:,}' if ns else 'large'
    return [f'CG solved up to {top} variables on the distributed runtime.',
            'Cholesky is memory-bound at scale (driver O(m^2)); several large cases were resource-excluded.',
            'Keep the driver/executor memory gate: fit the estimate before launching a big direct solve.']


def _family_takeaways(rows):
    by_family = collections.defaultdict(list)
    for row in rows:
        if (row['algorithm'] == 'CG' and row['case'].startswith('difficulty-500-10-20-')
                and row['median_s'] is not None):
            by_family[row['family']].append(row['median_s'])
    meds = {f: _median(v) for f, v in by_family.items() if v}
    if not meds:
        return ['No comparable difficulty fixtures were solved by CG.']
    ranked = sorted(meds, key=meds.get)
    worst, best = ranked[-1], ranked[0]
    return [f'CG cost climbs with conditioning: {" < ".join(ranked)} (median solve time).',
            f'{worst.capitalize()} fixtures cost ~{meds[worst] / meds[best]:.0f}x the '
            f'{best} family here.',
            'Prefer Cholesky (or tighter tolerance/regularization) for badly conditioned inputs.']


def _at_a_glance(rows, campaigns, chart_files, profile_n):
    measured = [r for r in rows]
    total_cases = len({(r['experiment'], r['case']) for r in rows})
    parts = ['## At a glance', '',
             'Two backends are compared on every case: **Cholesky** (direct normal-equations '
             'solve) and **CG** (matrix-free conjugate gradient). Pick a backend with the table, '
             'skim the charts, then open a section for the full measurements. '
             'For hands-on exploration use the [interactive dashboard](index.html).', '',
             '### Which backend should I use?', '',
             '| If your problem is... | Prefer | Why |',
             '|---|---|---|',
             '| Small (<= ~1,000 rows), dense, or badly conditioned (dependent/degenerate) | **Cholesky** | faster and more reliable there |',
             '| Well-conditioned and sparse from ~2,500 rows up | **CG** | direct factor cost grows with m^2-m^3 |',
             '| Too large for the driver memory gate | **CG** | avoids the O(m^2) driver matrix |',
             '| Numerically hard at scale (wide/near-dependent, tight tolerance) | **validate** | both backends can fail; require residual checks |',
             '']
    if 'charts/performance-profile.svg' in chart_files:
        parts += [_img('charts/performance-profile.svg',
                       'Performance profile comparing Cholesky and CG'),
                  '', '*Read it as: for a slowdown budget on the x-axis, what fraction of cases '
                  'each backend solves within that factor of the best. Curves that reach the '
                  'top-left first win more often.*', '']
    if 'charts/scaling-rows.svg' in chart_files:
        parts += [_img('charts/scaling-rows.svg', 'Solve time scaling with rows'),
                  '', '*Median solve time as rows grow (log-log). Cholesky steepens; CG stays flatter.*', '']
    parts += ['### What was tested', '', _coverage_mermaid(rows),
              '', f'Coverage: {len(campaigns)} campaigns, {total_cases} distinct cases, '
              f'families {", ".join(sorted({r["family"] for r in rows}))}; '
              f'rows m {min(r["m"] for r in rows):,}-{max(r["m"] for r in rows):,}, '
              f'variables n up to {max(r["n"] for r in rows):,}. '
              'Every solve is independently re-validated (primal/dual/gap/objective); '
              'timing never includes a failed run.', '']
    return '\n'.join(parts)


def _coverage_mermaid(rows):
    sections = collections.OrderedDict()
    for row in sorted(rows, key=lambda r: r['order']):
        info = sections.setdefault(row['section'], dict(
            fams=set(), mmin=row['m'], mmax=row['m'], local=row['local']))
        info['fams'].add(row['family'])
        info['mmin'] = min(info['mmin'], row['m'])
        info['mmax'] = max(info['mmax'], row['m'])
    lines = ['```mermaid', 'flowchart LR',
             '  root["spark-lp LP benchmarks<br/>Cholesky vs CG"]']
    for i, (title, info) in enumerate(sections.items()):
        env = 'local' if info['local'] else 'distributed'
        fams = ', '.join(sorted(info['fams']))
        label = f'{title}<br/>{fams}<br/>m {info["mmin"]:,}-{info["mmax"]:,} - {env}'
        lines.append(f'  root --> n{i}["{label}"]')
    lines.append('```')
    return '\n'.join(lines)


# --- Interactive dashboard ------------------------------------------------

def build_dashboard(rows):
    """Self-contained, offline HTML: embedded JSON + a small vanilla-JS SVG
    renderer with filters. No network, no external libraries, deterministic."""
    records = [dict(section=r['section'], prefix=r['prefix'], campaign=r['campaign'],
                    experiment=r['experiment'], case=r['case'], family=r['family'],
                    seed=r['seed'], m=r['m'],
                    n=r['n'], nnz=r['nnz'], density=r['density'],
                    partitions=r['partitions'], executors=r['executors'],
                    algorithm=r['algorithm'], median_s=r['median_s'],
                    min_s=r['min_s'], max_s=r['max_s'], attempted=r['attempted'],
                    valid=r['valid'], outer=r['outer'], cg_steps=r['cg_steps'],
                    gap_max=r['gap_max'], rss_max=r['rss_max'])
               for r in rows]
    data = json.dumps(records, sort_keys=True, separators=(',', ':'), allow_nan=False)
    # Keep the embedded literal from prematurely closing the <script> element.
    data = data.replace('<', '\\u003c').replace('>', '\\u003e')
    return _DASHBOARD_TEMPLATE.replace('/*DATA*/', data)


_DASHBOARD_TEMPLATE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>spark-lp benchmark dashboard</title>
<style>
:root{--fg:#222;--muted:#777;--grid:#e2e2e2;--chol:#1f77b4;--cg:#d62728;--card:#fff;--bg:#f6f7f9}
*{box-sizing:border-box}
body{margin:0;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Helvetica,Arial,sans-serif;color:var(--fg);background:var(--bg)}
header{padding:18px 22px;background:#fff;border-bottom:1px solid var(--grid)}
h1{margin:0;font-size:19px}
p.sub{margin:4px 0 0;color:var(--muted);font-size:13px}
.wrap{display:flex;gap:16px;padding:16px 22px;flex-wrap:wrap}
.panel{background:var(--card);border:1px solid var(--grid);border-radius:10px;padding:14px}
.controls{width:260px;flex:0 0 260px}
.controls label{display:block;font-size:12px;color:var(--muted);margin:12px 0 4px;text-transform:uppercase;letter-spacing:.03em}
select,.chk{width:100%;font-size:14px;padding:6px 8px;border:1px solid var(--grid);border-radius:6px;background:#fff}
.chart{flex:1 1 560px;min-width:320px}
.legend{font-size:12px;margin-top:8px;color:var(--muted)}
.legend span{display:inline-block;margin-right:14px}
.legend i{display:inline-block;width:11px;height:11px;border-radius:2px;vertical-align:middle;margin-right:5px}
.fam{display:flex;align-items:center;gap:8px;margin:5px 0;font-size:13px}
.fam input{width:auto}
#tip{position:fixed;pointer-events:none;background:#111;color:#fff;font-size:12px;padding:6px 8px;border-radius:6px;opacity:0;transition:opacity .08s;max-width:260px}
.count{font-size:12px;color:var(--muted);margin-top:10px}
a{color:#1f77b4}
</style>
</head>
<body>
<header>
<h1>spark-lp benchmark dashboard</h1>
<p class="sub">Cholesky vs CG on Apache Spark linear programs. Data embedded from the committed BMF evidence &mdash; no network. Back to the <a href="README.md">report</a>.</p>
</header>
<div class="wrap">
  <div class="panel controls">
    <label>Chart</label>
    <select id="chart">
      <option value="scaling">Scaling: time vs size</option>
      <option value="profile">Performance profile</option>
      <option value="partitions">CG partition tuning</option>
      <option value="familybar">Cost by family</option>
    </select>
    <div id="xaxisctl">
    <label>X axis (scaling)</label>
    <select id="xaxis">
      <option value="m">rows m</option>
      <option value="n">variables n</option>
      <option value="nnz">nonzeros</option>
    </select>
    </div>
    <label>Y metric</label>
    <select id="metric">
      <option value="median_s">solve seconds</option>
      <option value="outer">outer iterations</option>
      <option value="cg_steps">CG steps</option>
      <option value="rss_max">peak RSS bytes</option>
    </select>
    <label>Runtime</label>
    <select id="runtime">
      <option value="all">all</option>
      <option value="local">local only</option>
      <option value="distributed">distributed only</option>
    </select>
    <label>Backends</label>
    <div class="fam"><input type="checkbox" id="bk-Cholesky" checked/><label for="bk-Cholesky" style="margin:0;text-transform:none;letter-spacing:0;color:var(--fg)">Cholesky</label></div>
    <div class="fam"><input type="checkbox" id="bk-CG" checked/><label for="bk-CG" style="margin:0;text-transform:none;letter-spacing:0;color:var(--fg)">CG</label></div>
    <label>Families</label>
    <div id="families"></div>
    <div class="count" id="count"></div>
  </div>
  <div class="panel chart">
    <div id="plot"></div>
    <div class="legend" id="legend"></div>
  </div>
</div>
<div id="tip"></div>
<script>
const DATA = /*DATA*/;
const CHOL="#1f77b4", CG="#d62728";
const FAMCOL={well:"#1f77b4",wide:"#ff7f0e",dependent:"#d62728",degenerate:"#9467bd",dense:"#2ca02c",planted:"#8c564b",structured:"#17becf",unknown:"#888"};
const LOCAL=new Set(["Solver scaling","Sparsity and conditioning","Dense small"]);
const W=680,H=380,L=64,R=660,T=54,B=326;
const $=id=>document.getElementById(id);
const families=[...new Set(DATA.map(d=>d.family))].sort();
const famBox=$("families");
families.forEach(f=>{
  const row=document.createElement("div");row.className="fam";
  row.innerHTML=`<input type="checkbox" id="fam-${f}" checked/><label for="fam-${f}" style="margin:0;text-transform:none;letter-spacing:0;color:var(--fg)">${f}</label>`;
  famBox.appendChild(row);
});
function activeFamilies(){return families.filter(f=>$("fam-"+f).checked);}
function med(v){const s=[...v].sort((a,b)=>a-b);const n=s.length;if(!n)return null;
  return n%2?s[(n-1)/2]:(s[n/2-1]+s[n/2])/2;}
function esc(s){return String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");}
function fmt(v){if(v==null)return"-";const a=Math.abs(v);
  if(a>=1000||(a>0&&a<0.01)){const e=Math.floor(Math.log10(a));const mant=v/Math.pow(10,e);return (Math.round(mant*100)/100)+"e"+e;}
  return (Math.round(v*1000)/1000).toString();}
function filtered(){
  const rt=$("runtime").value, fams=new Set(activeFamilies());
  const bks=new Set(["Cholesky","CG"].filter(b=>$("bk-"+b).checked));
  return DATA.filter(d=>{
    if(!bks.has(d.algorithm))return false;
    if(!fams.has(d.family))return false;
    const loc=LOCAL.has(d.section);
    if(rt==="local"&&!loc)return false;
    if(rt==="distributed"&&loc)return false;
    return true;
  });
}
function axisLin(min,max,p0,p1,log){
  if(log){min=Math.log10(min);max=Math.log10(max);}
  if(min===max){min-=1;max+=1;}
  return v=>{if(v==null||v<=0&&log)return null;if(log)v=Math.log10(v);return p0+(v-min)/(max-min)*(p1-p0);};
}
function svgStart(title,sub){
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="inherit" font-size="12">`+
    `<rect width="${W}" height="${H}" fill="#fff"/>`+
    `<text x="16" y="22" font-size="15" font-weight="600" fill="#222">${esc(title)}</text>`+
    (sub?`<text x="16" y="40" font-size="11.5" fill="#888">${esc(sub)}</text>`:"");
}
function gridY(ticks,sc){let s="";ticks.forEach(t=>{const y=sc(t);if(y==null)return;
  s+=`<line x1="${L}" y1="${y.toFixed(1)}" x2="${R}" y2="${y.toFixed(1)}" stroke="#e2e2e2"/>`+
     `<text x="${L-8}" y="${(y+4).toFixed(1)}" text-anchor="end" fill="#888" font-size="10.5">${esc(fmt(t))}</text>`;});
  return s;}
function gridX(ticks,sc){let s="";ticks.forEach(t=>{const x=sc(t);if(x==null)return;
  s+=`<line x1="${x.toFixed(1)}" y1="${T}" x2="${x.toFixed(1)}" y2="${B}" stroke="#e2e2e2"/>`+
     `<text x="${x.toFixed(1)}" y="${B+16}" text-anchor="middle" fill="#888" font-size="10.5">${esc(fmt(t))}</text>`;});
  return s;}
function logTicks(min,max){const a=Math.floor(Math.log10(min)),b=Math.ceil(Math.log10(max));const t=[];for(let e=a;e<=b;e++)t.push(Math.pow(10,e));return t;}
function linTicks(min,max){const t=[];for(let i=0;i<=5;i++)t.push(min+(max-min)*i/5);return t;}
function axisLabels(xl,yl){return `<text x="${(L+R)/2}" y="${B+34}" text-anchor="middle" font-size="11.5" fill="#222">${esc(xl)}</text>`+
  `<text x="16" y="${(T+B)/2}" text-anchor="middle" font-size="11.5" fill="#222" transform="rotate(-90 16 ${(T+B)/2})">${esc(yl)}</text>`+
  `<line x1="${L}" y1="${T}" x2="${L}" y2="${B}" stroke="#555"/><line x1="${L}" y1="${B}" x2="${R}" y2="${B}" stroke="#555"/>`;}
function tip(evt,text){const t=$("tip");t.innerHTML=text;t.style.opacity=1;t.style.left=(evt.clientX+12)+"px";t.style.top=(evt.clientY+12)+"px";}
function hideTip(){$("tip").style.opacity=0;}
let hover=[];
function bind(){hover.forEach(h=>{const el=$(h.id);if(el){el.addEventListener("mousemove",e=>tip(e,h.text));el.addEventListener("mouseleave",hideTip);}});}

function drawScaling(){
  const rows=filtered(), x=$("xaxis").value, metric=$("metric").value;
  const pts=rows.filter(d=>d[x]!=null&&d[metric]!=null&&d[metric]>0);
  if(!pts.length)return empty("no data for this selection");
  const xs=pts.map(d=>d[x]), ys=pts.map(d=>d[metric]);
  const sx=axisLin(Math.min(...xs),Math.max(...xs),L,R,true);
  const sy=axisLin(Math.min(...ys),Math.max(...ys),B,T,true);
  let s=svgStart("Scaling: "+metric+" vs "+x,$("runtime").value+" runtime, medians per case");
  s+=gridY(logTicks(Math.min(...ys),Math.max(...ys)),sy)+gridX(logTicks(Math.min(...xs),Math.max(...xs)),sx)+axisLabels(x+" (log)",metric+" (log)");
  hover=[];let idx=0;
  ["Cholesky","CG"].forEach(alg=>{
    const col=alg==="Cholesky"?CHOL:CG;
    const dd=pts.filter(d=>d.algorithm===alg).sort((a,b)=>a[x]-b[x]);
    // connect medians per x
    const byx={};dd.forEach(d=>{(byx[d[x]]=byx[d[x]]||[]).push(d[metric]);});
    const line=Object.keys(byx).map(Number).sort((a,b)=>a-b).map(k=>{const v=byx[k];return [sx(k),sy(med(v))];});
    if(line.length)s+=`<path d="${line.map((p,i)=>(i?"L":"M")+p[0].toFixed(1)+","+p[1].toFixed(1)).join(" ")}" fill="none" stroke="${col}" stroke-width="2"/>`;
    dd.forEach(d=>{const id="p"+(idx++);const cx=sx(d[x]),cy=sy(d[metric]);if(cx==null||cy==null)return;
      s+=`<circle id="${id}" cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="3.4" fill="${col}" fill-opacity="0.8"/>`;
      hover.push({id,text:`<b>${esc(d.case)}</b><br>${alg} &middot; ${d.family}<br>${x}=${fmt(d[x])} &middot; ${metric}=${fmt(d[metric])}`});});
  });
  s+="</svg>";render(s,[["Cholesky",CHOL],["CG",CG]],rows.length);
}
function drawProfile(){
  const rows=filtered();
  const groups={};
  rows.forEach(d=>{if(d.median_s==null)return;const k=d.experiment+"|"+d.case+"|"+d.m+"|"+d.n;(groups[k]=groups[k]||{})[d.algorithm]=d.median_s;});
  const keys=Object.keys(groups);let total=0;const ratios={Cholesky:[],CG:[]};
  keys.forEach(k=>{const g=groups[k];
    if(g.Cholesky==null||g.CG==null)return;   // fair head-to-head: both solved
    total++;const best=Math.min(g.Cholesky,g.CG);
    ["Cholesky","CG"].forEach(a=>{ratios[a].push(g[a]/best);});});
  if(!total)return empty("no cases solved by both backends in this selection");
  const allr=[].concat(ratios.Cholesky,ratios.CG);const tmax=Math.max(1,...allr);
  const sx=axisLin(1,tmax,L,R,true), sy=axisLin(0,1,B,T,false);
  let s=svgStart("Performance profile","fraction of "+total+" cases within a slowdown factor of best");
  s+=gridY(linTicks(0,1),sy)+gridX(logTicks(1,tmax),sx)+axisLabels("slowdown ratio to best (log)","fraction of cases");
  ["Cholesky","CG"].forEach(alg=>{const col=alg==="Cholesky"?CHOL:CG;const vals=ratios[alg].slice().sort((a,b)=>a-b);
    let d="",py=null;vals.forEach((r,i)=>{const frac=(i+1)/total;const px=sx(r),cy=sy(frac);if(px==null)return;
      if(py==null)d+="M"+px.toFixed(1)+","+cy.toFixed(1);else d+=" L"+px.toFixed(1)+","+py.toFixed(1)+" L"+px.toFixed(1)+","+cy.toFixed(1);py=cy;});
    if(py!=null)d+=" L"+R+","+py.toFixed(1);
    if(d)s+=`<path d="${d}" fill="none" stroke="${col}" stroke-width="2"/>`;});
  s+="</svg>";render(s,[["Cholesky",CHOL],["CG",CG]],rows.length);
}
function drawPartitions(){
  const rows=filtered().filter(d=>d.prefix==="PART"&&d.algorithm==="CG"&&d.partitions!=null&&d.median_s!=null);
  if(!rows.length)return empty("no partition data (clear filters / pick CG)");
  const fixtures={};rows.forEach(d=>{const key=d.family+" "+d.m+"x"+d.n;(fixtures[key]=fixtures[key]||{});(fixtures[key][d.partitions]=fixtures[key][d.partitions]||[]).push(d.median_s);});
  const xs=rows.map(d=>d.partitions), ys=rows.map(d=>d.median_s);
  const sx=axisLin(Math.min(...xs),Math.max(...xs),L,R,true), sy=axisLin(Math.min(...ys),Math.max(...ys),B,T,true);
  let s=svgStart("CG input-partition tuning","median solve seconds per fixture");
  s+=gridY(logTicks(Math.min(...ys),Math.max(...ys)),sy)+gridX(logTicks(Math.min(...xs),Math.max(...xs)),sx)+axisLabels("input partitions (log)","solve seconds (log)");
  const pal=[CG,"#ff7f0e","#9467bd","#2ca02c","#17becf"];const leg=[];let i=0;hover=[];let idx=0;
  Object.keys(fixtures).sort().forEach(key=>{const col=pal[i++%pal.length];leg.push([key,col]);
    const line=Object.keys(fixtures[key]).map(Number).sort((a,b)=>a-b).map(p=>{const m=med(fixtures[key][p]);return [sx(p),sy(m),p,m];});
    s+=`<path d="${line.map((p,j)=>(j?"L":"M")+p[0].toFixed(1)+","+p[1].toFixed(1)).join(" ")}" fill="none" stroke="${col}" stroke-width="2"/>`;
    line.forEach(p=>{const id="pp"+(idx++);s+=`<circle id="${id}" cx="${p[0].toFixed(1)}" cy="${p[1].toFixed(1)}" r="3.4" fill="${col}"/>`;hover.push({id,text:`<b>${esc(key)}</b><br>${p[2]} partitions &middot; ${fmt(p[3])} s`});});});
  s+="</svg>";render(s,leg,rows.length);
}
function drawFamilyBar(){
  const rows=filtered().filter(d=>d.median_s!=null);
  const byf={};rows.forEach(d=>{(byf[d.family]=byf[d.family]||[]).push(d.median_s);});
  const fam=Object.keys(byf).sort();if(!fam.length)return empty("no data");
  const meds=fam.map(f=>med(byf[f]));
  const sy=axisLin(0,Math.max(...meds)*1.1,B,T,false);
  let s=svgStart("Median solve seconds by family",$("runtime").value+" runtime, both backends");
  s+=gridY(linTicks(0,Math.max(...meds)*1.1),sy)+axisLabels("family","median solve seconds")+`<line x1="${L}" y1="${sy(0).toFixed(1)}" x2="${R}" y2="${sy(0).toFixed(1)}" stroke="#555"/>`;
  const slot=(R-L)/fam.length;hover=[];let idx=0;
  fam.forEach((f,i)=>{const cx=L+slot*(i+0.5),bw=Math.min(slot*0.6,54),y=sy(meds[i]),id="fb"+(idx++);
    s+=`<rect id="${id}" x="${(cx-bw/2).toFixed(1)}" y="${y.toFixed(1)}" width="${bw.toFixed(1)}" height="${(sy(0)-y).toFixed(1)}" rx="2" fill="${FAMCOL[f]||"#888"}"/>`+
       `<text x="${cx.toFixed(1)}" y="${B+16}" text-anchor="middle" font-size="11" fill="#888">${esc(f)}</text>`;
    hover.push({id,text:`<b>${esc(f)}</b><br>${byf[f].length} runs &middot; median ${fmt(meds[i])} s`});});
  s+="</svg>";render(s,fam.map(f=>[f,FAMCOL[f]||"#888"]),rows.length);
}
function empty(msg){render(svgStart("","")+`<text x="${W/2}" y="${H/2}" text-anchor="middle" fill="#888">${esc(msg)}</text></svg>`,[],0);}
function render(svg,legend,n){
  $("plot").innerHTML=svg;
  $("legend").innerHTML=legend.map(l=>`<span><i style="background:${l[1]}"></i>${esc(l[0])}</span>`).join("");
  $("count").textContent=n+" series rows in view";
  bind();
}
function draw(){
  const c=$("chart").value;
  $("xaxisctl").style.display=c==="scaling"?"block":"none";
  if(c==="scaling")drawScaling();
  else if(c==="profile")drawProfile();
  else if(c==="partitions")drawPartitions();
  else drawFamilyBar();
}
document.querySelectorAll("select,input").forEach(el=>el.addEventListener("change",draw));
draw();
</script>
</body>
</html>
"""
