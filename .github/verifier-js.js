// Vérifie la syntaxe de tout le JavaScript de VoieLibre.html avant de construire l'APK.
// La compilation Java ne regarde pas la page : sans ce contrôle, une faute de JavaScript partirait
// chez les collègues (écran blanc ou fonction morte). Ici, la construction s'arrête et indique la ligne.
//
// Le fichier contient des scripts directs et deux pages entières rangées dans des
// <script type="text/plain"> (Planning et Voir ma journée), dont les propres scripts se terminent par <\/script>.
// Chaque script est seulement compilé (vm.Script), jamais exécuté.
'use strict';
const fs = require('fs');
const vm = require('vm');

const chemin = process.argv[2] || 'app/src/main/assets/VoieLibre.html';
const html = fs.readFileSync(chemin, 'utf8');
const ligneDe = pos => html.slice(0, pos).split('\n').length;

let nb = 0, erreurs = 0;
function verifier(code, ligneDebut, nom) {
  nb++;
  try {
    new vm.Script(code, { filename: nom });
  } catch (e) {
    erreurs++;
    // ligne de l'erreur dans le bloc → ligne dans VoieLibre.html
    const m = String(e.stack || '').match(new RegExp(nom.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ':(\\d+)'));
    const ligne = m ? ligneDebut + (+m[1]) - 1 : ligneDebut;
    console.log(`::error file=${chemin},line=${ligne}::JavaScript invalide (${nom}) : ${e.message}`);
  }
}

// Scripts de premier niveau : <script ...> … </script>
const balise = /<script([^>]*)>([\s\S]*?)<\/script>/g;
let m;
while ((m = balise.exec(html))) {
  const attrs = m[1], contenu = m[2];
  const debut = m.index + m[0].indexOf('>') + 1;
  const ligne = ligneDe(debut);
  if (/type\s*=\s*["']?text\/plain/i.test(attrs)) {
    // page rangée en texte : ses scripts se ferment par <\/script>
    const id = (attrs.match(/id\s*=\s*["']?([\w-]+)/) || [])[1] || 'page';
    const interne = /<script([^>]*)>([\s\S]*?)<\\\/script>/g;
    let k;
    while ((k = interne.exec(contenu))) {
      if (/type\s*=/i.test(k[1]) && !/javascript|module/i.test(k[1])) continue;
      const pos = debut + k.index + k[0].indexOf('>') + 1;
      verifier(k[2], ligneDe(pos), `${id}@${ligneDe(pos)}`);
    }
  } else if (!/type\s*=/i.test(attrs) || /javascript|module/i.test(attrs)) {
    verifier(contenu, ligne, `script@${ligne}`);
  }
}

if (nb === 0) { console.log(`::error file=${chemin}::aucun script trouvé (fichier déplacé ?)`); process.exit(1); }
if (erreurs) { console.log(`${erreurs} script(s) en erreur sur ${nb}`); process.exit(1); }
console.log(`JavaScript de ${chemin} : ${nb} scripts vérifiés, aucune erreur de syntaxe.`);
