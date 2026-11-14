import math, subprocess, sys, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H, FPS = 1080, 1920, 30
D = os.path.dirname(os.path.abspath(__file__))
FD = D + "/fonts/"
AMIRI = FD + "fontsource-amiri-5.3.0/package/files/amiri-arabic-700-normal.woff"
CAIRO = FD + "fontsource-cairo-5.3.0/package/files/cairo-arabic-700-normal.woff"
CAIRO_R = FD + "fontsource-cairo-5.3.0/package/files/cairo-arabic-400-normal.woff"
F = lambda p, s: ImageFont.truetype(p, s)

BG1, BG2 = (10, 46, 52), (6, 24, 30)
GOLD = (222, 184, 108)
CREAM = (246, 238, 220)
TEAL = (64, 160, 150)
SKIN = (233, 196, 160)   # plain head, no facial features

def ar(n):
    return str(n).translate(str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩"))

HADITHS = [
    dict(t="إنما الأعمال بالنيات، وإنما لكل امرئ ما نوى", n=1, r="عمر بن الخطاب رضي الله عنه",
         title="النية", app="قبل أن تذاكر أو تعمل أو تساعد أحدًا، جدّد نيتك واجعلها لله؛ فيتحول عملك العادي إلى عبادة وأجر.", scene="intent"),
    dict(t="لا يؤمن أحدكم حتى يحب لأخيه ما يحب لنفسه", n=13, r="أنس بن مالك رضي الله عنه",
         title="حب الخير للآخرين", app="إذا عرفت فرصة خير أو معلومة نافعة فشاركها مع أخيك، وافرح لنجاحه كما تفرح لنجاحك.", scene="gift"),
    dict(t="المسلم من سلم المسلمون من لسانه ويده", n=10, r="عبد الله بن عمرو رضي الله عنهما",
         title="كفّ الأذى", app="لا تؤذِ أحدًا بكلمة جارحة أو سخرية أو تعليق سيئ على مواقع التواصل، ولا تمدّ يدك بأذى.", scene="phone"),
    dict(t="ومن كان يؤمن بالله واليوم الآخر فليقل خيرًا أو ليصمت", n=6018, r="أبي هريرة رضي الله عنه", part=True,
         title="الكلمة بميزان", app="قبل أن تتكلم اسأل نفسك: هل كلامي خير؟ فإن لم يكن كذلك فالصمت أفضل.", scene="silence"),
    dict(t="ليس الشديد بالصُّرَعة، إنما الشديد الذي يملك نفسه عند الغضب", n=6114, r="أبي هريرة رضي الله عنه",
         title="ضبط النفس", app="عند الغضب: اسكت، وغيّر وضعك، وتعوّذ بالله من الشيطان، وتوضأ؛ فالقوة الحقيقية في ضبط النفس.", scene="calm"),
    dict(t="إن الدين يسر، ولن يشادّ الدين أحد إلا غلبه، فسددوا وقاربوا وأبشروا", n=39, r="أبي هريرة رضي الله عنه",
         title="التدرّج والاعتدال", app="لا تُثقل على نفسك بما لا تطيق؛ اختر عملًا صالحًا صغيرًا وداوم عليه، وتدرّج خطوة بخطوة.", scene="stairs"),
    dict(t="خيركم من تعلّم القرآن وعلّمه", n=5027, r="عثمان بن عفان رضي الله عنه",
         title="تعلّم وعلّم", app="احفظ ولو آية كل يوم، وعلّمها لأهلك أو لصديقك؛ فتكون من خير الناس.", scene="teach"),
    dict(t="والكلمة الطيبة صدقة", n=2989, r="أبي هريرة رضي الله عنه", part=True,
         title="صدقة بلا مال", app="ابتسم واشكر، وقل كلمة طيبة لوالديك وأصدقائك وحتى للغرباء؛ فكل كلمة طيبة صدقة.", scene="kind"),
    dict(t="نعمتان مغبون فيهما كثير من الناس: الصحة والفراغ", n=6412, r="ابن عباس رضي الله عنهما",
         title="اغتنم وقتك", app="نظّم يومك، واستثمر صحتك وفراغك في تعلّم نافع وعبادة ورياضة قبل أن تنشغل أو تمرض.", scene="time"),
    dict(t="يسّروا ولا تعسّروا، وبشّروا ولا تنفّروا", n=69, r="أنس بن مالك رضي الله عنه",
         title="التيسير والبشارة", app="سهّل على الناس أمورهم، وقدّم لهم الأمل والبشارة، وابتعد عن التشديد والتنفير.", scene="help"),
]

# ---------- drawing helpers ----------
def background():
    im = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(im)
    for y in range(H):
        k = y / H
        d.line([(0, y), (W, y)], fill=tuple(int(BG1[i] * (1 - k) + BG2[i] * k) for i in range(3)))
    # subtle geometric star pattern
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    o = ImageDraw.Draw(ov)
    s = 180
    for gy in range(-1, H // s + 2):
        for gx in range(-1, W // s + 2):
            cx, cy = gx * s + (s // 2 if gy % 2 else 0), gy * s
            pts = []
            for i in range(16):
                r = 46 if i % 2 == 0 else 26
                a = math.pi / 8 * i
                pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
            o.polygon(pts, outline=(222, 184, 108, 22), width=2)
    im.paste(ov, (0, 0), ov)
    return im

BASE = background()

def wrap(text, font, maxw):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if font.getlength(t, direction="rtl") <= maxw:
            cur = t
        else:
            lines.append(cur); cur = w
    if cur: lines.append(cur)
    return lines

def draw_center(d, text, font, y, fill, maxw=900, spacing=1.55):
    text = text.replace(":", "،").replace(".", "")
    lines = wrap(text, font, maxw)
    lh = font.size * spacing
    for i, ln in enumerate(lines):
        w = font.getlength(ln, direction="rtl")
        d.text(((W - w) / 2, y + i * lh), ln, font=font, fill=fill, direction="rtl", language="ar")
    return y + len(lines) * lh

def ornament(d, y, w=360):
    cx = W / 2
    d.line([(cx - w, y), (cx - 30, y)], fill=GOLD, width=3)
    d.line([(cx + 30, y), (cx + w, y)], fill=GOLD, width=3)
    d.polygon([(cx, y - 16), (cx + 16, y), (cx, y + 16), (cx - 16, y)], outline=GOLD, width=3)

def rounded(d, box, fill, r=40, outline=None, width=3):
    d.rounded_rectangle(box, r, fill=fill, outline=outline, width=width)

# ---------- faceless figure ----------
def person(d, x, y, s=1.0, color=TEAL, arms=("down", "down"), sit=False, small=False):
    """x,y = feet center. Head is a plain circle with NO facial features."""
    if small: s *= 0.72
    hr = 34 * s
    body_h = 150 * s if not sit else 95 * s
    top = y - body_h - (0 if sit else 0)
    # body (robe-like rounded shape)
    d.rounded_rectangle([x - 48 * s, top, x + 48 * s, y], 30 * s, fill=color)
    # neck + head
    hy = top - hr - 6 * s
    d.ellipse([x - hr, hy - hr, x + hr, hy + hr], fill=SKIN)
    # simple head cover / hair cap (still no features)
    d.chord([x - hr, hy - hr, x + hr, hy + hr], 180, 360, fill=(60, 50, 45))
    # arms
    sh_y = top + 22 * s
    for side, pose in zip((-1, 1), arms):
        sx = x + side * 46 * s
        if pose == "down":
            ex, ey = sx + side * 14 * s, sh_y + 95 * s
        elif pose == "out":
            ex, ey = sx + side * 85 * s, sh_y + 40 * s
        elif pose == "up":
            ex, ey = sx + side * 50 * s, sh_y - 85 * s
        elif pose == "front":
            ex, ey = sx + side * 70 * s, sh_y + 60 * s
        elif pose == "heart":
            ex, ey = x + side * 6 * s, sh_y + 30 * s
        else:
            ex, ey = pose
            ex, ey = x + ex * s, sh_y + ey * s
        d.line([(sx, sh_y), (ex, ey)], fill=color, width=int(22 * s), joint="curve")
        d.ellipse([ex - 12 * s, ey - 12 * s, ex + 12 * s, ey + 12 * s], fill=SKIN)
    return (x, hy, hr)

def heart(d, cx, cy, s, fill=(220, 90, 100)):
    d.ellipse([cx - s, cy - s * 0.8, cx, cy + 0.2 * s], fill=fill)
    d.ellipse([cx, cy - s * 0.8, cx + s, cy + 0.2 * s], fill=fill)
    d.polygon([(cx - s * 0.98, cy - 0.15 * s), (cx + s * 0.98, cy - 0.15 * s), (cx, cy + s * 1.05)], fill=fill)

def bubble(d, box, tail, fill=CREAM):
    d.rounded_rectangle(box, 30, fill=fill)
    x0, y0, x1, y1 = box
    tx, ty = tail
    mx = (x0 + x1) / 2
    d.polygon([(mx - 25, y1 - 2), (mx + 25, y1 - 2), (tx, ty)], fill=fill)

def book(d, cx, cy, s=1.0, col=(150, 90, 60)):
    d.polygon([(cx, cy), (cx - 70 * s, cy - 18 * s), (cx - 70 * s, cy + 40 * s), (cx, cy + 58 * s)], fill=CREAM, outline=col, width=4)
    d.polygon([(cx, cy), (cx + 70 * s, cy - 18 * s), (cx + 70 * s, cy + 40 * s), (cx, cy + 58 * s)], fill=CREAM, outline=col, width=4)
    for k in range(3):
        d.line([(cx - 58 * s, cy - 2 * s + k * 14 * s), (cx - 12 * s, cy + 10 * s + k * 14 * s)], fill=(170, 150, 120), width=2)
        d.line([(cx + 12 * s, cy + 10 * s + k * 14 * s), (cx + 58 * s, cy - 2 * s + k * 14 * s)], fill=(170, 150, 120), width=2)

def scene(name, t):
    """Illustration panel 900x620, t = 0..1 animation phase."""
    im = Image.new("RGBA", (900, 620), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, 899, 619], 50, fill=(18, 70, 76, 255), outline=GOLD, width=4)
    floor = 560
    d.line([(40, floor), (860, floor)], fill=(40, 110, 110), width=6)
    bob = math.sin(t * math.pi * 4) * 6
    if name == "intent":
        d.rounded_rectangle([420, 440, 760, 460], 8, fill=(140, 95, 60))
        d.line([(450, 460), (450, floor)], fill=(140, 95, 60), width=10)
        d.line([(730, 460), (730, floor)], fill=(140, 95, 60), width=10)
        book(d, 590, 385, 0.9)
        person(d, 330, floor, 1.15, arms=((95, 85), "heart"))
        heart(d, 330, 150 + bob, 40, fill=GOLD)
        for i in range(8):
            a = i * math.pi / 4 + t * 2
            d.line([(330 + 62 * math.cos(a), 135 + bob + 62 * math.sin(a)), (330 + 82 * math.cos(a), 135 + bob + 82 * math.sin(a))], fill=GOLD, width=5)
    elif name == "gift":
        person(d, 270, floor, 1.1, arms=("down", "front"))
        person(d, 640, floor, 1.1, color=(176, 120, 80), arms=("front", "down"))
        gx = 400 + 50 * min(1, t * 1.6)
        d.rectangle([gx, 330, gx + 90, 410], fill=(205, 85, 95))
        d.rectangle([gx + 38, 330, gx + 52, 410], fill=GOLD)
        d.rectangle([gx, 362, gx + 90, 376], fill=GOLD)
        heart(d, 455, 230 + bob, 30)
    elif name == "phone":
        person(d, 450, floor, 1.2, arms=("heart", ((60, 40))))
        d.rounded_rectangle([510, 330, 590, 460], 14, fill=(40, 40, 50))
        d.rounded_rectangle([518, 342, 582, 446], 8, fill=(220, 240, 235))
        heart(d, 550, 392, 18)
        for i, (bx, by) in enumerate([(90, 90), (630, 70)]):
            bubble(d, [bx, by + bob, bx + 190, by + 110 + bob], (bx + 120, by + 170), fill=CREAM)
            heart(d, bx + 95, by + 55 + bob, 26, fill=TEAL if i else (220, 90, 100))
    elif name == "silence":
        person(d, 280, floor, 1.1, arms=("down", (5, -5)))
        person(d, 640, floor, 1.1, color=(176, 120, 80), arms=("down", "down"))
        bubble(d, [120, 60 + bob, 420, 200 + bob], (280, 250))
        d.text((270 - 40, 85 + bob), "خير", font=F(CAIRO, 64), fill=(20, 90, 90), direction="rtl", language="ar")
        heart(d, 370, 128 + bob, 20)
        bubble(d, [520, 90, 760, 200], (620, 250), fill=(200, 220, 215))
        for k in range(3):
            d.ellipse([585 + k * 40, 135, 605 + k * 40, 155], fill=(90, 120, 120))
    elif name == "calm":
        person(d, 450, floor, 1.2, arms=("heart", "heart"))
        a = int(200 * (1 - t))
        for k in range(3):
            d.ellipse([560 + k * 50, 70 + k * 20, 680 + k * 50, 150 + k * 20], fill=tuple(int(c1*(a-k*40)/255+c2*(1-(a-k*40)/255)) for c1,c2 in zip((200,70,70),(18,70,76))) if a-k*40>0 else (18,70,76))
        # water drops (wudu)
        for k in range(5):
            yy = (60 + k * 90 + t * 300) % 420 + 60
            xx = 150 + (k % 2) * 60
            d.polygon([(xx, yy - 22), (xx - 14, yy + 6), (xx + 14, yy + 6)], fill=(120, 190, 230))
            d.ellipse([xx - 14, yy - 6, xx + 14, yy + 18], fill=(120, 190, 230))
    elif name == "stairs":
        for i in range(6):
            d.rectangle([150 + i * 110, floor - (i + 1) * 60, 860, floor - i * 60], fill=(40, 120, 115) if i % 2 else (50, 135, 128))
        step = min(5, int(t * 6))
        px = 210 + step * 110
        person(d, px, floor - step * 60, 0.9, arms=("out", "down"))
        d.polygon([(760, 120), (775, 160), (818, 162), (784, 188), (796, 230), (760, 205), (724, 230), (736, 188), (702, 162), (745, 160)], fill=GOLD)
    elif name == "teach":
        person(d, 260, floor, 1.2, arms=("down", "front"))
        person(d, 620, floor, 0.85, color=(176, 120, 80), arms=("front", "down"))
        book(d, 440, 320 + bob * 0.5, 1.05, col=(30, 110, 100))
        for k in range(3):
            a = t * 6 + k * 2.1
            d.ellipse([440 + 120 * math.cos(a) - 8, 230 + 40 * math.sin(a) - 8, 440 + 120 * math.cos(a) + 8, 230 + 40 * math.sin(a) + 8], fill=GOLD)
    elif name == "kind":
        person(d, 280, floor, 1.1, arms=("down", "out"))
        person(d, 640, floor, 1.1, color=(176, 120, 80), arms=("down", "heart"))
        bubble(d, [140, 70 + bob, 440, 210 + bob], (290, 250))
        # flower
        cx, cy = 290, 140 + bob
        for k in range(6):
            a = k * math.pi / 3 + t
            d.ellipse([cx + 32 * math.cos(a) - 22, cy + 32 * math.sin(a) - 22, cx + 32 * math.cos(a) + 22, cy + 32 * math.sin(a) + 22], fill=(230, 130, 150))
        d.ellipse([cx - 20, cy - 20, cx + 20, cy + 20], fill=GOLD)
        heart(d, 640, 140 - bob, 34)
    elif name == "time":
        # running figure
        x = 260 + 40 * math.sin(t * math.pi * 2)
        person(d, x, floor, 1.0, arms=((-80, 30), (70, -40)))
        cx, cy, r = 640, 260, 140
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=CREAM, outline=GOLD, width=10)
        for k in range(12):
            a = k * math.pi / 6
            d.line([(cx + 115 * math.cos(a), cy + 115 * math.sin(a)), (cx + 130 * math.cos(a), cy + 130 * math.sin(a))], fill=(60, 60, 60), width=6)
        a = -math.pi / 2 + t * math.pi * 4
        d.line([(cx, cy), (cx + 105 * math.cos(a), cy + 105 * math.sin(a))], fill=(200, 70, 70), width=8)
        d.line([(cx, cy), (cx + 70 * math.cos(a / 12 - 1), cy + 70 * math.sin(a / 12 - 1))], fill=(40, 40, 40), width=12)
        d.ellipse([cx - 12, cy - 12, cx + 12, cy + 12], fill=(40, 40, 40))
    elif name == "help":
        rise = min(1, t * 1.5)
        person(d, 320, floor, 1.15, arms=("down", (95, 60)))
        hx = 600
        person(d, hx, floor, 1.0 * (0.7 + 0.3 * rise), color=(176, 120, 80), arms=((-110, 40 + 40 * (1 - rise)), "down"))
        d.line([(110, floor - 6), (230, floor - 6)], fill=GOLD, width=8)
        d.polygon([(760, 90), (775, 130), (818, 132), (784, 158), (796, 200), (760, 175), (724, 200), (736, 158), (702, 132), (745, 130)], fill=GOLD)
    return im

# ---------- slides ----------
def ease(x):
    x = max(0, min(1, x)); return x * x * (3 - 2 * x)

def frame_intro(t):
    im = BASE.copy(); d = ImageDraw.Draw(im)
    a = ease(t * 2)
    ornament(d, 620)
    draw_center(d, "١٠ أحاديث نبوية", F(CAIRO, 110), 700, GOLD)
    draw_center(d, "من صحيح البخاري", F(AMIRI, 90), 880, CREAM)
    ornament(d, 1080)
    draw_center(d, "مع تطبيقها في حياتنا اليومية", F(CAIRO_R, 56), 1160, (200, 225, 220))
    return fade(im, a)

def frame_hadith(h, i, t):
    im = BASE.copy(); d = ImageDraw.Draw(im)
    # number badge
    cx, cy = W // 2, 300
    pts = [(cx + 95 * math.cos(math.pi / 4 * k + math.pi / 8), cy + 95 * math.sin(math.pi / 4 * k + math.pi / 8)) for k in range(8)]
    d.polygon(pts, outline=GOLD, width=5)
    d.ellipse([cx - 70, cy - 70, cx + 70, cy + 70], outline=GOLD, width=3)
    s = ar(i + 1); f = F(CAIRO, 84)
    d.text((cx - f.getlength(s) / 2, cy - 72), s, font=f, fill=GOLD, direction="rtl", language="ar")
    draw_center(d, h["title"], F(CAIRO, 70), 440, CREAM)
    draw_center(d, "عن " + h["r"] + " أن رسول الله ﷺ قال", F(AMIRI, 44), 600, (190, 215, 210))
    rounded(d, [70, 720, W - 70, 1330], (14, 60, 66), outline=GOLD)
    fs = 74 if len(h["t"]) < 50 else 64
    lines = wrap(h["t"], F(AMIRI, fs), 820)
    hh = len(lines) * fs * 1.7
    draw_center(d, h["t"], F(AMIRI, fs), 1025 - hh / 2, (255, 246, 222), maxw=820, spacing=1.7)
    src = "رواه البخاري، رقم " + ar(h["n"]) + (" (جزء من حديث)" if h.get("part") else "")
    draw_center(d, src, F(CAIRO, 46), 1400, GOLD)
    ornament(d, 1520)
    return fade(im, ease(t * 4) * ease((1 - t) * 4))

def frame_app(h, i, t):
    im = BASE.copy(); d = ImageDraw.Draw(im)
    draw_center(d, "التطبيق العملي", F(CAIRO, 76), 150, GOLD)
    draw_center(d, h["title"], F(CAIRO_R, 52), 270, CREAM)
    sc = scene(h["scene"], t)
    im.paste(sc, (90, 390), sc)
    d = ImageDraw.Draw(im)
    rounded(d, [70, 1080, W - 70, 1640], (14, 60, 66), outline=TEAL)
    f = F(CAIRO, 54)
    lines = wrap(h["app"], f, 840)
    hh = len(lines) * 54 * 1.65
    draw_center(d, h["app"], f, 1360 - hh / 2, CREAM, maxw=840, spacing=1.65)
    draw_center(d, "الحديث " + ar(i + 1) + " من ١٠", F(CAIRO_R, 40), 1740, (150, 190, 185))
    return fade(im, ease(t * 4) * ease((1 - t) * 4))

def frame_outro(t):
    im = BASE.copy(); d = ImageDraw.Draw(im)
    ornament(d, 640)
    draw_center(d, "بلّغوا عني ولو آية", F(AMIRI, 100), 720, GOLD)
    draw_center(d, "رواه البخاري، رقم ٣٤٦١", F(CAIRO_R, 48), 900, CREAM)
    ornament(d, 1040)
    draw_center(d, "شارك الفيديو لتعمّ الفائدة", F(CAIRO, 52), 1120, (200, 225, 220))
    return fade(im, ease(t * 3) * ease((1 - t) * 3))

def fade(im, a):
    if a >= 0.999: return im
    return Image.blend(Image.new("RGB", (W, H), BG2), im, a)

SEG = [("intro", None, None, 4.5)]
for i, h in enumerate(HADITHS):
    SEG.append(("hadith", h, i, 8.0))
    SEG.append(("app", h, i, 8.0))
SEG.append(("outro", None, None, 5.0))

def render(path, preview=None):
    if preview is not None:
        k, h, i, dur = SEG[preview[0]]
        t = preview[1]
        fn = {"intro": lambda: frame_intro(t), "outro": lambda: frame_outro(t),
              "hadith": lambda: frame_hadith(h, i, t), "app": lambda: frame_app(h, i, t)}[k]
        fn().save(path); return
    p = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                          "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", path], stdin=subprocess.PIPE)
    for k, h, i, dur in SEG:
        n = int(dur * FPS)
        static = None
        for f in range(n):
            t = f / (n - 1)
            if k == "intro": im = frame_intro(t)
            elif k == "outro": im = frame_outro(t)
            elif k == "hadith":
                # static content, only fade changes: cache full-opacity frame
                if static is None: static = frame_hadith(h, i, 0.5)
                a = ease(t * 4) * ease((1 - t) * 4)
                im = fade(static, a)
            else: im = frame_app(h, i, t)
            p.stdin.write(im.tobytes())
        print("done", k, i, flush=True)
    p.stdin.close(); p.wait()

if __name__ == "__main__":
    if len(sys.argv) > 2:
        render(sys.argv[1], (int(sys.argv[2]), float(sys.argv[3])))
    else:
        render(sys.argv[1])
