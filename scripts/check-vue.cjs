const fs = require('fs');
const { execSync } = require('child_process');
const path = process.argv[2];
const text = fs.readFileSync(path, 'utf8');
const re = /<script\s+setup>([\s\S]*?)<\/script>/g;
let m;
let idx = 0;
let hadError = false;
while ((m = re.exec(text)) !== null) {
  const tmp = path + '.script.' + idx + '.mjs';
  fs.writeFileSync(tmp, m[1], 'utf8');
  try {
    execSync(`node --check "${tmp}"`, { stdio: 'pipe' });
    console.log('OK', tmp);
  } catch (e) {
    console.log('SYNTAX_ERROR', tmp, '\n' + (e.stderr ? e.stderr.toString() : e.message));
    hadError = true;
  }
  fs.unlinkSync(tmp);
  idx += 1;
}
if (hadError) process.exit(1);
