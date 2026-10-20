import asyncio, sys
from playwright.async_api import async_playwright
sys.path.insert(0, '.')
from layers import *
def svg(mask, size, mono=False):
    t = 'transform="translate(18 18) scale(0.6667)"'
    clip = {'circle': f'<circle {t} cx="54" cy="54" r="54"/>',
            'squircle': f'<path {t} d="M54,0 C92,0 108,16 108,54 C108,92 92,108 54,108 C16,108 0,92 0,54 C0,16 16,0 54,0Z"/>',
            'rounded': f'<rect {t} width="108" height="108" rx="24"/>'}[mask]
    # Launchers show the central 72x72 of the 108 canvas (scale 1.5).
    body = (f'<g clip-path="url(#m)"><path d="M0,0h108v108h-108z" fill="#E8E0F8"/>' + f'<g transform="{MONO_TF}">' + MONO_BODY.replace('#000', '#3B2A7A') + '</g></g>') if mono \
        else f'<g clip-path="url(#m)">{BG_BODY}<g transform="{FG_TF}">{FG_BODY}</g></g>'
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">
<defs>{BG}{FG_DEFS}<clipPath id="m" clipPathUnits="userSpaceOnUse">{clip}</clipPath></defs>{body}</svg>'''
async def main():
    cells = ''
    for mask in ['circle','squircle','rounded']:
        cells += '<div class=row>' + ''.join(f'<div style="width:{s}px">{svg(mask,s)}</div>' for s in [192,96,64,48,36]) + f'<span>{mask}</span></div>'
    cells += '<div class=row>' + ''.join(f'<div>{svg("circle",s,mono=True)}</div>' for s in [192,96,48]) + '<span>themed (monochrome)</span></div>'
    html = f'<html><body style="margin:0;padding:20px;background:#1e1e24;font:14px sans-serif;color:#ccc"><style>.row{{display:flex;gap:28px;align-items:center;margin-bottom:24px}}</style>{cells}' \
           f'<div class=row style="background:#f2f2f7;padding:16px;border-radius:12px">' + ''.join(f'<div>{svg("circle",s)}</div>' for s in [96,64,48]) + '<span style="color:#333">on light wallpaper</span></div></body></html>'
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/opt/pw-browsers/chromium-1194/chrome-linux/chrome')
        pg = await b.new_page(viewport={'width':760,'height':900})
        await pg.set_content(html); await pg.screenshot(path='preview.png', full_page=True); await b.close()
asyncio.run(main())
