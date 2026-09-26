// Demo history for two fake teammates. Each session is one terminal; `day` is how many days ago it happened.
// Commands are plain strings (exit 0) or {cmd, exit, error} for failures. A failure followed within a few commands
// by the command that fixed it is exactly what the learning loop turns into a team fix.

const fail = (cmd, error, exit = 1) => ({ cmd, exit, error });

export const TEAM = { name: 'Recall demo', password: 'recall-demo-2026' };
export const DAKSH = { email: 'daksh@recall.demo', displayName: 'Daksh' };
export const PRIYA = { email: 'priya@recall.demo', displayName: 'Priya' };

/** Everyday work: about 170 successful commands. */
export const HISTORY = {
  daksh: [
    { day: 29, repo: 'shop', commands: ['cd ~/work/shop', 'git status', 'git pull --rebase', 'npm ci', 'npm run dev', 'git checkout -b feature/cart-total', 'code .', 'npm test', 'git add -A', 'git commit -m "cart total rounding"'] },
    { day: 27, repo: 'shop', commands: ['git status', 'git diff', 'git reset --soft HEAD~1', 'git add src/cart.ts', 'git commit -m "fix cart total rounding"', 'git push -u origin feature/cart-total', 'gh pr create --fill'] },
    { day: 25, repo: 'shop', commands: ['docker compose up -d', 'docker compose ps', 'docker compose logs -f api', 'docker exec -it shop-db-1 psql -U app shop', 'mysql -u root -pS3cretRoot shop', 'docker compose down'] },
    { day: 22, repo: 'shop', commands: ['kubectl config use-context staging', 'kubectl get pods -n staging', 'kubectl logs deploy/api -n staging', 'kubectl describe pod api-7d9f8b6c5-x2x9k -n staging', 'kubectl rollout restart deployment/api -n staging', 'kubectl rollout status deployment/api -n staging'] },
    { day: 20, repo: 'shop', commands: ['git fetch --prune', 'git branch -a', 'git branch -d feature/cart-total', 'git log --oneline -15', 'git stash', 'git checkout main', 'git pull', 'git stash pop'] },
    { day: 18, repo: 'shop', commands: ['du -sh node_modules', 'rm -rf node_modules', 'npm cache clean --force', 'npm ci', 'npm outdated', 'npm update', 'npm audit fix', 'npm test -- --watch=false'] },
    { day: 15, repo: 'shop', commands: ['docker system df', 'docker system prune -af', 'docker images', 'docker build -t shop/api:1.4 .', 'docker run -p 8080:8080 shop/api:1.4', 'docker ps -a'] },
    { day: 12, repo: 'shop', commands: ['git status', 'git add -A', 'git commit -m "wip checkout flow"', 'git reset --soft HEAD~1', 'git add src/checkout', 'git commit -m "checkout flow"', 'git push'] },
    { day: 9, repo: 'shop-infra', commands: ['cd ~/work/shop-infra', 'terraform init', 'terraform plan', 'terraform apply', 'kubectl apply -f k8s/api.yaml', 'kubectl get svc -n staging', 'kubectl port-forward svc/api 8080:80 -n staging'] },
    { day: 6, repo: 'shop', commands: ['lsof -i :3000', 'npx kill-port 3000', 'npm run dev', 'curl -s http://localhost:3000/health', 'tail -f logs/app.log', 'grep -rn "TODO" src'] },
    { day: 3, repo: 'shop', commands: ['git status', 'git commit --amend -m "checkout: handle empty cart"', 'git push --force-with-lease', 'git log --oneline -5', 'git reset --soft HEAD~1', 'git commit -m "checkout: handle empty cart"'] },
    { day: 1, repo: 'shop', commands: ['npm run build', 'npm run lint', 'npx prettier --write src', 'npm test', 'git tag v1.4.0', 'git push origin v1.4.0'] },
    { day: 16, repo: 'shop', commands: ['redis-cli ping', 'redis-cli flushall', 'pg_dump -h localhost -U app shop > backup.sql', 'psql -h localhost -U app shop -f migrations/007.sql', 'nslookup api.acme.dev', 'curl -I https://api.acme.dev/health'] },
    { day: 0, repo: 'shop', commands: ['git pull --rebase', 'npm ci', 'npm run dev', 'git checkout -b fix/login-timeout', 'npm test -- login', 'git add -A', 'git commit -m "login: raise timeout"', 'git push -u origin fix/login-timeout'] },
  ],
  priya: [
    { day: 28, repo: 'shop-ml', commands: ['cd ~/work/shop-ml', 'python -m venv .venv', 'source .venv/bin/activate', 'pip install -r requirements.txt', 'pip install --upgrade pip', 'pytest', 'pytest -k recommend -x'] },
    { day: 26, repo: 'shop-ml', commands: ['jupyter lab', 'python train.py --epochs 5', 'python -m http.server 8000', 'pip freeze > requirements.txt', 'git add requirements.txt', 'git commit -m "pin deps"'] },
    { day: 23, repo: 'shop', commands: ['git clone git@github.com:acme/shop.git', 'cd shop', 'nvm use 20', 'npm ci', 'npm run dev', 'git switch -c feature/search-filters'] },
    { day: 21, repo: 'shop', commands: ['docker compose up -d --build', 'docker compose logs -f web', 'docker exec -it shop-web-1 sh', 'docker compose restart web', 'docker volume ls', 'docker compose down -v'] },
    { day: 17, repo: 'shop', commands: ['git rebase -i HEAD~3', 'git push --force-with-lease', 'git cherry-pick 3f2a91c', 'git log --oneline --graph -20', 'git restore --staged src/search.ts', 'git diff --staged'] },
    { day: 14, repo: 'shop-ml', commands: ['kubectl get pods -n ml', 'kubectl logs deploy/recommender -n ml', 'kubectl scale deployment/recommender --replicas=3 -n ml', 'kubectl top pods -n ml', 'kubectl rollout undo deployment/recommender -n ml'] },
    { day: 11, repo: 'shop', commands: ['npm install -D vitest', 'npx vitest run', 'npm run test -- --coverage', 'npx eslint src --fix', 'git add -A', 'git commit -m "search filters tests"'] },
    { day: 8, repo: 'shop-infra', commands: ['ssh-keygen -t ed25519 -C "priya@acme.dev"', 'ssh-copy-id deploy@staging.acme.dev', 'ssh deploy@staging.acme.dev', 'scp dist.tar.gz deploy@staging.acme.dev:/srv/shop', 'tar -xzf dist.tar.gz', 'df -h'] },
    { day: 5, repo: 'shop', commands: ['git fetch --prune', 'git switch main', 'git pull --rebase', 'git branch -d feature/search-filters', 'git stash list', 'git clean -fd'] },
    { day: 4, repo: 'shop', commands: ['mvn -v', 'mvn clean install -DskipTests', 'mvn dependency:tree', './mvnw spring-boot:run', 'java -jar target/pricing.jar'] },
    { day: 2, repo: 'shop', commands: ['docker ps', 'docker logs --tail 200 shop-api-1', 'docker stop shop-api-1', 'docker rm -f shop-api-1', 'docker compose up -d api', 'git reset --soft HEAD~1'] },
    { day: 0, repo: 'shop-ml', commands: ['source .venv/bin/activate', 'pytest -x', 'python -m pip install -r requirements-dev.txt', 'ruff check .', 'git add -A', 'git commit -m "faster feature store"', 'git push'] },
  ],
};

/** 15 real errors and what fixed them. Each becomes one terminal session: failure, maybe a look around, the fix. */
export const FIXES = [
  { who: 'daksh', day: 24, repo: 'shop', steps: [
    fail('npm run dev', 'node:events:497\n      throw er; // Unhandled \'error\' event\n      ^\nError: listen EADDRINUSE: address already in use :::3000\n    at Server.setupListenHandle [as _listen2] (node:net:1872:16)\n{ code: \'EADDRINUSE\', errno: -98, syscall: \'listen\', address: \'::\', port: 3000 }'),
    'lsof -i :3000', 'npx kill-port 3000'] },
  { who: 'priya', day: 22, repo: 'shop', steps: [
    fail('git push', 'To github.com:acme/shop.git\n ! [rejected]        feature/search-filters -> feature/search-filters (fetch first)\nerror: failed to push some refs to \'github.com:acme/shop.git\'\nhint: Updates were rejected because the remote contains work that you do not have locally.'),
    'git pull --rebase'] },
  { who: 'priya', day: 19, repo: 'shop', steps: [
    fail('git switch recall-demo', 'fatal: invalid reference: recall-demo', 128),
    'git switch -c recall-demo'] },
  { who: 'daksh', day: 17, repo: 'shop-ml', steps: [
    fail('python app.py', 'Traceback (most recent call last):\n  File "/home/daksh/work/shop-ml/app.py", line 3, in <module>\n    import requests\nModuleNotFoundError: No module named \'requests\''),
    'pip install -r requirements.txt'] },
  { who: 'priya', day: 16, repo: 'shop', steps: [
    fail('docker compose up -d', 'Error response from daemon: driver failed programming external connectivity on endpoint shop-db-1: Bind for 0.0.0.0:5432 failed: port is already allocated'),
    'docker stop legacy-postgres'] },
  { who: 'daksh', day: 14, repo: 'shop', steps: [
    fail('docker run --name shop-db -p 5432:5432 postgres:16', 'docker: Error response from daemon: Conflict. The container name "/shop-db" is already in use by container "4f3c2a1b9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b". You have to remove (or rename) that container to be able to reuse that name.', 125),
    'docker rm -f shop-db'] },
  { who: 'priya', day: 13, repo: 'shop', steps: [
    fail('mvn clean install', '[INFO] BUILD FAILURE\n[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project pricing: Fatal error compiling: error: invalid target release: 21'),
    'export JAVA_HOME=/usr/lib/jvm/temurin-21'] },
  { who: 'daksh', day: 12, repo: 'shop-infra', steps: [
    fail('kubectl get pods -n staging', 'E0924 10:15:02.123456   4242 memcache.go:265] couldn\'t get current server API group list: Get "https://127.0.0.1:6443/api?timeout=32s": dial tcp 127.0.0.1:6443: connect: connection refused\nThe connection to the server 127.0.0.1:6443 was refused - did you specify the right host or port?'),
    'kubectl config use-context staging'] },
  { who: 'priya', day: 10, repo: 'shop', steps: [
    fail('npm install', 'npm ERR! code ERESOLVE\nnpm ERR! ERESOLVE unable to resolve dependency tree\nnpm ERR! Found: react@18.3.1\nnpm ERR! Could not resolve dependency: peer react@"^17.0.0" from react-table@7.8.0'),
    'npm install --legacy-peer-deps'] },
  { who: 'daksh', day: 9, repo: 'shop-infra', steps: [
    fail('./deploy.sh', 'bash: ./deploy.sh: Permission denied', 126),
    'chmod +x deploy.sh'] },
  { who: 'priya', day: 7, repo: 'shop-ml', steps: [
    fail('git commit -m "first model"', '*** Please tell me who you are.\n\nRun\n\n  git config --global user.email "you@example.com"\n  git config --global user.name "Your Name"\n\nfatal: unable to auto-detect email address (got \'priya@laptop.(none)\')', 128),
    'git config --global user.email "priya@acme.dev"'] },
  { who: 'daksh', day: 6, repo: 'shop', steps: [
    fail('node server.js', 'node:internal/modules/cjs/loader:1228\n  throw err;\n  ^\n\nError: Cannot find module \'express\'\nRequire stack:\n- /home/daksh/work/shop/server.js'),
    'npm install'] },
  { who: 'priya', day: 5, repo: 'shop', steps: [
    fail('psql -h localhost -U app shop', 'psql: error: connection to server at "localhost" (127.0.0.1), port 5432 failed: Connection refused\n\tIs the server running on that host and accepting TCP/IP connections?', 2),
    'docker compose up -d db'] },
  { who: 'daksh', day: 4, repo: 'shop', steps: [
    fail('npm run build', '<--- Last few GCs --->\nFATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory'),
    'export NODE_OPTIONS=--max-old-space-size=4096'] },
  { who: 'priya', day: 3, repo: 'shop', steps: [
    fail('git pull', 'error: Your local changes to the following files would be overwritten by merge:\n\tsrc/search.ts\nPlease commit your changes or stash them before you merge.\nAborting'),
    'git stash'] },
];
