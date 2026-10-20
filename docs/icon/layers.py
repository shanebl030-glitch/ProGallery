# Single source of truth for the icon geometry (108x108 adaptive canvas).
BG = '''
<linearGradient id="bg" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse">
  <stop offset="0" stop-color="#4F46E5"/><stop offset="1" stop-color="#9333EA"/>
</linearGradient>
<radialGradient id="glow" cx="30" cy="22" r="70" gradientUnits="userSpaceOnUse">
  <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.22"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
</radialGradient>'''
BG_BODY = '<path d="M0,0h108v108h-108z" fill="url(#bg)"/><path d="M0,0h108v108h-108z" fill="url(#glow)"/>'

FG_DEFS = '''
<linearGradient id="sky" x1="0" y1="40" x2="0" y2="70" gradientUnits="userSpaceOnUse">
  <stop offset="0" stop-color="#FDBA74"/><stop offset="1" stop-color="#F472B6"/>
</linearGradient>'''
# Back card (tilted), front card, photo window, sun, mountains, play badge.
FG_BODY = '''
<path d="M35.6,38.1 L68.9,32.2 A5,5 0 0 1 74.7,36.3 L78.4,57.3 A5,5 0 0 1 74.3,63.1 L41,69 A5,5 0 0 1 35.2,64.9 L31.5,43.9 A5,5 0 0 1 35.6,38.1 Z" fill="#FFFFFF" fill-opacity="0.38"/>
<path d="M36,40 H70 A6,6 0 0 1 76,46 V70 A6,6 0 0 1 70,76 H36 A6,6 0 0 1 30,70 V46 A6,6 0 0 1 36,40 Z" fill="#FFFFFF"/>
<path d="M37,44 H69 A3,3 0 0 1 72,47 V69 A3,3 0 0 1 69,72 H37 A3,3 0 0 1 34,69 V47 A3,3 0 0 1 37,44 Z" fill="url(#sky)"/>
<path d="M61,52.5 m-4,0 a4,4 0 1 1 8,0 a4,4 0 1 1 -8,0" fill="#FFF7ED"/>
<path d="M34,64 L45,53 L53,61 L57,57 L72,69 V69 A3,3 0 0 1 69,72 H37 A3,3 0 0 1 34,69 Z" fill="#4338CA"/>
<path d="M34,67 L42,61 L52,72 H37 A3,3 0 0 1 34,69 Z" fill="#312E81"/>
<path d="M75,73 m-10,0 a10,10 0 1 1 20,0 a10,10 0 1 1 -20,0" fill="#FFFFFF"/>
<path d="M72,68.2 L80.4,73 L72,77.8 Z" fill="#6D28D9"/>
'''
# Monochrome (themed icon): card frame with cut-out window, sun and mountains (no badge).
MONO_BODY = '''
<path fill="#000" fill-rule="evenodd" d="M36,40 H70 A6,6 0 0 1 76,46 V70 A6,6 0 0 1 70,76 H36 A6,6 0 0 1 30,70 V46 A6,6 0 0 1 36,40 Z M37,44 H69 A3,3 0 0 1 72,47 V69 A3,3 0 0 1 69,72 H37 A3,3 0 0 1 34,69 V47 A3,3 0 0 1 37,44 Z"/>
<path fill="#000" d="M61,52.5 m-4,0 a4,4 0 1 1 8,0 a4,4 0 1 1 -8,0"/>
<path fill="#000" d="M34,64 L45,53 L53,61 L57,57 L72,69 V69 A3,3 0 0 1 69,72 H37 A3,3 0 0 1 34,69 Z"/>
'''
# Group transform: scale about the artwork centre and re-centre in the safe zone.
FG_TF = 'translate(54 54) scale(0.9) translate(-57.5 -57.5)'
MONO_TF = 'translate(54 54) scale(0.95) translate(-53 -58)'
