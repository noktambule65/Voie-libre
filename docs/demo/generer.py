"""Planning de démonstration de Voie libre, au format de l'export Graou (.ics).

Sert à l'examen Google Play (rubrique « Accès à l'appli ») et aux captures d'écran :
journées, trains et gares inventés, aucun planning réel. Couvre plus de deux ans pour ne pas se périmer.
Relancer : python3 docs/demo/generer.py  (écrit docs/demo/planning-demo.ics)
"""
import datetime as dt, os

DEBUT, FIN = dt.date(2026, 10, 26), dt.date(2029, 1, 7)   # un lundi, ~2 ans

# Journées de service : code, résidence de PS/FS, lignes du déroulé (comme Graou).
# Ligne « *** h1 -> h2 | DE -> A | numéro » = train conduit ; sinon tâche ou EV.
JS = {
 'N201': ('TS', '05:34', '13:02', [
   '05:44 | TS | Marche', '05:55 | TS | PC 860101',
   '*** 06:10 -> 06:52 | TS -> PAU | 860101',
   '07:05 -> 08:20 | PAU | Coupure',
   '*** 08:34 -> 09:16 | PAU -> TS | 860108', '09:24 | TS | MS 860108',
   '10:05 | TS | EQ PREND 860113', '*** 10:15 -> 11:02 | TS -> LNZ | 860113',
   '*** 11:40 -> 12:30 | LNZ -> TS | 860120', '12:40 | TS | MS 860120']),
 'N214': ('TS', '12:51', '20:47', [
   '13:05 | TS | RS 860131', '*** 13:20 -> 15:12 | TS -> TE | 860131',
   '15:12 -> 16:40 | TE | Coupure',
   '*** 16:52 -> 18:44 | TE -> TS | 860146', '18:55 -> 19:35 | TS -> LD | EV 860151',
   '*** 19:48 -> 20:12 | LD -> TS | 860156', '20:20 | TS | MS 860156']),
 'N225': ('TS', '07:12', '15:20', [
   '07:30 | TS | PC 860161', '*** 07:45 -> 08:27 | TS -> PAU | 860161',
   '*** 08:50 -> 10:05 | PAU -> BYE | 860163',
   '10:05 -> 11:30 | BYE | Coupure',
   '*** 11:45 -> 13:00 | BYE -> PAU | 860168', '13:10 | PAU | EQ PREND 860171',
   '*** 13:25 -> 14:07 | PAU -> TS | 860171', '14:20 | TS | MS 860171']),
 'N236': ('TS', '15:26', '23:03', [
   '15:40 | TS | PC 860181', '*** 15:55 -> 16:45 | TS -> MTJ | 860181',
   '16:55 -> 17:45 | MTJ -> LUC | BUS 860183',
   '17:45 -> 19:05 | LUC | Coupure',
   '19:15 -> 20:05 | LUC -> MTJ | BUS 860186',
   '*** 20:20 -> 21:12 | MTJ -> TS | 860188', '21:25 | TS | EQ PREND 860191',
   '*** 21:35 -> 21:58 | TS -> LD | 860191', '*** 22:15 -> 22:38 | LD -> TS | 860194',
   '22:45 | TS | MS 860194']),
 'N242': ('TS', '10:31', '17:45', [
   '10:45 | TS | RS 860201', '*** 11:00 -> 12:52 | TS -> TE | 860201',
   '12:52 -> 14:00 | TE | Coupure',
   '*** 14:10 -> 16:00 | TE -> AX | 860205',
   '16:10 -> 17:30 | AX -> TS | TAXI']),
}
# Découché : aller (jour 1) puis retour (jour 2), repos hors résidence à Toulouse
DEC = (('N250', '13:26', '20:14', [
         '13:40 | TS | PC 860211', '*** 13:55 -> 15:47 | TS -> TE | 860211',
         '15:47 -> 16:50 | TE | Coupure', '*** 17:05 -> 18:55 | TE -> FX | 860215',
         '*** 19:10 -> 20:00 | FX -> TE | 860218', '20:05 | TE | MS 860218'],
        'TS', 'TE'),
       ('N251', '05:23', '13:17', [
         '05:40 | TE | PC 860221', '*** 05:55 -> 07:47 | TE -> TS | 860221',
         '07:47 -> 09:10 | TS | Coupure', '09:15 | TS | EQ PREND 860226',
         '*** 09:30 -> 11:22 | TS -> TE | 860226',
         '11:40 -> 13:05 | TE -> TS | EV 860229'],
        'TE', 'TS'))

# Cycle de deux semaines (lundi → dimanche) ; 'DEC' = aller, 'DEC2' = retour
CYCLE = ['N201', 'N214', 'N225', 'DEC', 'DEC2', 'RP', 'RP',
         'N236', 'N242', 'd', 'N201', 'N225', 'RP', 'RP']
CONGES = {(2026, 12, 21), (2027, 4, 19), (2027, 8, 2), (2027, 8, 9), (2027, 12, 20),
          (2028, 4, 17), (2028, 7, 31), (2028, 8, 7), (2028, 12, 18)}   # lundis des semaines de CA

def hm(h): return h.replace(':', '') + '00'
def duree(a, b):
    m = (int(b[:2]) * 60 + int(b[3:])) - (int(a[:2]) * 60 + int(a[3:]))
    if m < 0: m += 1440
    return '%02dh%02d' % divmod(m, 60)
def esc(t): return t.replace('\\', '\\\\').replace(';', '\\;').replace(',', '\\,').replace('\n', '\\n')
def plier(l):   # lignes de 75 octets au plus (RFC 5545)
    out, b = [], l.encode()
    while len(b) > 75:
        n = 75 if not out else 74
        while (b[n] & 0xC0) == 0x80: n -= 1
        out.append(b[:n].decode()); b = b[n:]
    out.append(b.decode())
    return '\r\n '.join(out)

evts, uid = [], 0
def ev(champs):
    global uid; uid += 1
    champs = ['UID:demo-%05d@voielibre' % uid, 'DTSTAMP:20261114T000000Z'] + champs
    evts.append('BEGIN:VEVENT\r\n' + '\r\n'.join(plier(c) for c in champs) + '\r\nEND:VEVENT')

def jour_entier(j, code):
    f = j + dt.timedelta(days=1)
    ev(['DTSTART;VALUE=DATE:' + j.strftime('%Y%m%d'), 'DTEND;VALUE=DATE:' + f.strftime('%Y%m%d'), 'SUMMARY:' + code])

j = DEBUT
while j <= FIN:
    lundi = j - dt.timedelta(days=j.weekday())
    if (lundi.year, lundi.month, lundi.day) in CONGES and j.weekday() < 5:
        jour_entier(j, 'CA'); j += dt.timedelta(days=1); continue
    t = CYCLE[((j - DEBUT).days) % 14]
    if t in ('RP', 'd'):
        jour_entier(j, t)
    elif t == 'DEC':
        (c1, d1, f1, l1, p1, s1), (c2, d2, f2, l2, p2, s2) = DEC
        k = j + dt.timedelta(days=1)
        desc = '%s %s -> %s | RHR Toulouse (%s) | %s %s -> %s\n\n' % (c1, d1, f1, duree(f1, d2), c2, d2, f2)
        desc += '[PS] %s %s\n%s\n[FS] %s %s\n\n' % (p1, d1, '\n'.join(l1), s1, f1)
        desc += '[PS] %s %s\n%s\n[FS] %s %s\n' % (p2, d2, '\n'.join(l2), s2, f2)
        ev(['DTSTART:' + j.strftime('%Y%m%d') + 'T' + hm(d1), 'DTEND:' + k.strftime('%Y%m%d') + 'T' + hm(f2),
            'SUMMARY:%s %s' % (c1, c2), 'LOCATION:Toulouse', 'DESCRIPTION:' + esc(desc)])
    elif t == 'DEC2':
        pass   # couvert par l'aller
    else:
        r, d, f, lignes = JS[t]
        desc = '%s %s\n\n[PS] %s %s\n%s\n[FS] %s %s\n' % (t, duree(d, f), r, d, '\n'.join(lignes), r, f)
        ev(['DTSTART:' + j.strftime('%Y%m%d') + 'T' + hm(d), 'DTEND:' + j.strftime('%Y%m%d') + 'T' + hm(f),
            'SUMMARY:' + t, 'DESCRIPTION:' + esc(desc)])
    j += dt.timedelta(days=1)

ics = ('BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Voie libre//Planning de demonstration//FR\r\n'
       'CALSCALE:GREGORIAN\r\nX-WR-CALNAME:Voie libre - démonstration\r\n' + '\r\n'.join(evts) + '\r\nEND:VCALENDAR\r\n')
chemin = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'planning-demo.ics')
with open(chemin, 'w', encoding='utf-8', newline='') as f: f.write(ics)
print(chemin, len(evts), 'événements', len(ics.encode()) // 1024, 'Ko')
