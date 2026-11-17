'use strict';

const $ = id => document.getElementById(id);
const android = location.hostname === 'appassets.androidplatform.net';
const nomes = { claude: 'Claude', codex: 'Codex' };
const cores = { claude: '#f0a486', codex: '#6ae5c6' };
const tipos = { nota: 'Nota', pasta: 'Pasta', raiz: 'Vault', tag: 'Tag', fantasma: 'Link sem destino', agente: 'Agente' };
const ops = { ler: 'Leitura', escrever: 'Escrita', apagar: 'Exclusão', listar: 'Listagem', buscar: 'Busca', comando: 'Comando' };
const reduzir = matchMedia('(prefers-reduced-motion: reduce)');
const defaults = {
  autoGirar: false, movimento: !reduzir.matches, trilhas: true, pastas: false, notas: true,
  tags: false, fantasmas: false, rotulos: 'nunca', qualidade: android ? 'media' : 'alta'
};
const ajustes = { ...defaults };
try {
  const salvos = JSON.parse(localStorage.getItem('rede-ajustes')) || {};
  for (const chave in defaults) {
    if (typeof defaults[chave] === 'boolean' && typeof salvos[chave] === 'boolean') ajustes[chave] = salvos[chave];
  }
  if (['auto', 'sempre', 'nunca'].includes(salvos.rotulos)) ajustes.rotulos = salvos.rotulos;
  if (['alta', 'media', 'baixa'].includes(salvos.qualidade)) ajustes.qualidade = salvos.qualidade;
} catch (_) {
  // Preferências podem estar indisponíveis em navegação privada.
}
const avatares = Object.fromEntries(Object.keys(nomes).map(agente => [
  agente, Personagens.montar($(agente + '-avatar'), agente)
]));
const motor = new Motor3D($('canvas'));
let grafo = null;
let eventos = new Map();
let desde = 0;
let painel = 'grafo';
let notaAtual = null;
let timer;
let atualizando = false;
let precisaLogin = false;
let geracao = 0;
let contaAndroid = false;

function alerta(texto) {
  $('aviso').textContent = texto;
  $('aviso').hidden = !texto;
}

function conexao(texto, ok = false) {
  if ($('conexao').textContent !== texto) $('conexao').textContent = texto;
  $('conexao').dataset.ok = ok;
}

function aplicarAjustes() {
  const movimento = ajustes.movimento && !reduzir.matches;
  document.body.classList.toggle('sem-movimento', !movimento);
  motor.opcoes({
    ...ajustes, autoGirar: ajustes.autoGirar && movimento, particulas: movimento, movimento
  });
}
for (const campo of document.querySelectorAll('[data-op]')) {
  const chave = campo.dataset.op;
  if (campo.type === 'checkbox') campo.checked = ajustes[chave];
  else campo.value = ajustes[chave];
  campo.addEventListener('change', () => {
    ajustes[chave] = campo.type === 'checkbox' ? campo.checked : campo.value;
    aplicarAjustes();
    try { localStorage.setItem('rede-ajustes', JSON.stringify(ajustes)); } catch (_) { /* opcional */ }
  });
}
reduzir.addEventListener('change', aplicarAjustes);
aplicarAjustes();
new ResizeObserver(() => motor.redimensionar()).observe($('grafo'));

async function api(rota, options = {}) {
  const resposta = await fetch('/api/' + rota, {
    ...options, cache: 'no-store', signal: AbortSignal.timeout(12000)
  });
  if (!resposta.ok) throw Object.assign(new Error('HTTP ' + resposta.status), { status: resposta.status });
  return resposta.json();
}

function abrirPainel(qual) {
  $('espaco').dataset.visao = qual;
  const lateral = qual === 'grafo' ? 'atividade' : qual;
  for (const el of document.querySelectorAll('.painel')) el.hidden = el.id !== lateral;
  if (qual !== 'detalhe') {
    painel = qual;
    notaAtual = null;
    motor.selecionar(null);
  }
  for (const el of document.querySelectorAll('[data-painel]')) {
    el.setAttribute('aria-pressed', String(el.dataset.painel === painel));
  }
  document.querySelector('.lateral').scrollTop = 0;
}
document.querySelectorAll('[data-painel]').forEach(el => el.addEventListener('click', () => abrirPainel(el.dataset.painel)));
document.querySelector('.skip').addEventListener('click', () => abrirPainel('grafo'));
$('voltar').onclick = () => abrirPainel(painel);
$('enquadrar').onclick = () => { abrirPainel('grafo'); motor.enquadrar(); };
$('mais').onclick = () => motor.zoom(0.78);
$('menos').onclick = () => motor.zoom(1.28);

const numero = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 });
const hora = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' });
function bytes(n) {
  if (n == null) return '—';
  if (n >= 1e6) return numero.format(n / 1e6) + ' MB';
  if (n >= 1e3) return numero.format(n / 1e3) + ' kB';
  return numero.format(n) + ' B';
}

function botao(rotulo, acao) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = rotulo;
  b.onclick = acao;
  return b;
}

function corNo(n) {
  if (n.tipo === 'agente') return cores[n.id.split(':')[1]];
  if (n.tipo === 'tag') return '#e8c777';
  if (n.tipo === 'fantasma') return '#b6a8aa';
  if (n.tipo !== 'nota') return '#849da9';
  const paleta = ['#75d2ef', '#88d7c0', '#98b7f4', '#efd19d', '#9ccad7'];
  let i = 0;
  for (const c of n.pasta || n.id) i = (i * 31 + c.codePointAt(0)) >>> 0;
  return paleta[i % paleta.length];
}

function atualizarGrafo(novo) {
  const primeiro = !grafo;
  grafo = novo;
  motor.definirGrafo(novo.nos.map(n => ({ ...n, cor: corNo(n) })), novo.arestas);
  if (primeiro) motor.enquadrar();
  if (notaAtual && !motor.no(notaAtual)) abrirPainel(painel);
  const m = novo.metricas;
  $('metricas').textContent = numero.format(m.notas) + ' notas · ' + numero.format(m.links) + ' links';
  $('fonte-grafo').textContent = novo.fonte === 'obsidian'
    ? 'Grafo resolvido pelo Obsidian.'
    : 'Grafo lido do disco. A resolução de links pode divergir do Obsidian.';
  buscar();
}

function normalizar(t) { return t.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase(); }
function buscar() {
  const termo = normalizar($('busca').value.trim());
  const resultados = $('resultados');
  resultados.replaceChildren();
  resultados.hidden = !termo || !grafo;
  const combina = n => n.tipo === 'nota' && normalizar(n.id + ' ' + n.tags.join(' ')).includes(termo);
  motor.definirFiltro(termo ? combina : null);
  if (resultados.hidden) return;
  const encontrados = grafo.nos.filter(combina).slice(0, 15);
  for (const n of encontrados) {
    const li = document.createElement('li');
    li.append(botao(n.rotulo, () => mostrarNo(n, true)));
    resultados.append(li);
  }
  if (!encontrados.length) {
    const vazio = document.createElement('li');
    vazio.className = 'vazio';
    vazio.textContent = 'Nenhuma nota encontrada.';
    resultados.append(vazio);
  }
}
$('busca').oninput = buscar;
$('busca').onkeydown = e => {
  if (e.key === 'Enter') $('resultados').querySelector('button')?.click();
  if (e.key === 'Escape') { $('busca').value = ''; buscar(); }
};

async function mostrarNo(n, focar = false) {
  if (!n) { abrirPainel(painel); return; }
  $('busca').value = '';
  buscar();
  if (n.tipo === 'agente') {
    abrirPainel('atividade');
    if (focar) motor.focar(n.id);
    return;
  }
  if (focar) motor.focar(n.id);
  motor.selecionar(n.id);
  notaAtual = n.id;
  $('nota-tipo').textContent = tipos[n.tipo] || 'Nó';
  $('nota-titulo').textContent = n.rotulo;
  $('nota-meta').textContent = n.tipo === 'nota' ? n.id + ' · ' + bytes(n.bytes) : '';
  $('nota-texto').textContent = n.tipo === 'nota' ? 'Carregando…' : '';
  $('nota-links').replaceChildren();
  abrirPainel('detalhe');
  try {
    const nota = n.tipo === 'nota' ? await api('nota?id=' + encodeURIComponent(n.id)) : null;
    if (notaAtual !== n.id) return;
    if (nota) {
      $('nota-meta').textContent = n.id + ' · ' + bytes(nota.bytes) + (nota.mod ? ' · ' + hora.format(nota.mod) : '');
      $('nota-texto').textContent = nota.texto + (nota.cortada ? '\n\n[Prévia limitada a 96 kB]' : '');
    }
    const ligados = nota ? [...new Set([...nota.saida, ...nota.entrada])] :
      grafo.arestas.flatMap(([a, b]) => a === n.id ? [b] : b === n.id ? [a] : []);
    for (const id of ligados) {
      const alvo = motor.no(id);
      if (alvo) $('nota-links').append(botao(alvo.rotulo, () => mostrarNo(alvo, true)));
    }
  } catch (_) {
    if (notaAtual === n.id) $('nota-texto').textContent = 'Não foi possível abrir a nota. Tente novamente.';
  }
}
motor.on('selecionar', n => mostrarNo(n, !!n && matchMedia('(max-width: 820px)').matches));

function pintarEventos() {
  const lista = $('eventos');
  const fragmento = document.createDocumentFragment();
  const ultimos = Array.from(eventos.values()).slice(-14).reverse();
  for (const e of ultimos) {
    const li = document.createElement('li');
    li.className = e.agente;
    const n = motor.no(e.no);
    const b = botao('', () => { if (n) mostrarNo(n, true); });
    const strong = document.createElement('strong');
    strong.textContent = nomes[e.agente] + ' · ' + (ops[e.op] || e.ferramenta);
    const meta = document.createElement('small');
    const status = e.ok === false ? ' · falhou' : e.ok == null ? ' · resultado ausente no log' : '';
    meta.textContent = (n?.rotulo || 'Vault') + ' · ' + hora.format(e.t) + ' · ' + bytes(e.envio + e.retorno) + status;
    b.append(strong, meta);
    li.append(b);
    fragmento.append(li);
  }
  if (!ultimos.length) {
    const li = document.createElement('li');
    li.className = 'suave';
    li.textContent = 'Nenhuma chamada do Obsidian nos logs recentes.';
    fragmento.append(li);
  }
  lista.replaceChildren(fragmento);
}

function atualizarVivo(vivo) {
  let mudou = false;
  for (const e of vivo.eventos) {
    const chave = e.id || [e.agente, e.sessao, e.t, e.ferramenta, e.no].join('|');
    const anterior = eventos.get(chave);
    if (!anterior || anterior.ok !== e.ok || anterior.retorno !== e.retorno) {
      mudou = true;
      eventos.set(chave, e);
      if (Date.now() - e.t < 20000) {
        const agente = 'agente:' + e.agente;
        motor.pulsar(e.no);
        if (!anterior) motor.particula(agente, e.no, cores[e.agente], 1.1);
        if (e.retorno) motor.particula(e.no, agente, cores[e.agente], 1.1);
      }
    }
  }
  if (eventos.size > 240) eventos = new Map(Array.from(eventos).slice(-240));
  desde = Math.max(desde, vivo.agora - 60000);
  if (mudou || !eventos.size) pintarEventos();
  motor.definirTrilhas(vivo.trilhas);
  for (const [agente, avatar] of Object.entries(avatares)) {
    const info = vivo.agentes[agente];
    const estado = info?.sessao ? info.estado : 'offline';
    $(agente + '-estado').textContent = Personagens.ESTADOS[estado];
    const ultimo = Array.from(eventos.values()).findLast(e => e.agente === agente);
    $(agente + '-fala').textContent = info?.sessao ? Personagens.fala(agente, info, ultimo) : 'Sem sessões locais recentes.';
    avatar.definir(estado, info?.tokens_min);
  }
  pintarTrafego(vivo.trafego);
  conexao(vivo.obsidian.ativo ? 'Obsidian conectado' : 'Obsidian indisponível', vivo.obsidian.ativo);
}

function dados(id, linhas) {
  $(id).replaceChildren(...linhas.flatMap(([chave, valor]) => {
    const dt = document.createElement('dt'), dd = document.createElement('dd');
    dt.textContent = chave;
    dd.textContent = valor;
    return [dt, dd];
  }));
}

function pintarTrafego(t) {
  dados('mcp', Object.entries(nomes).flatMap(([id, nome]) => {
    const a = t.mcp.por_agente[id];
    return [[nome + ' · enviado', bytes(a?.envio ?? 0)], [nome + ' · resposta', bytes(a?.retorno ?? 0)], [nome + ' · chamadas', a?.chamadas ?? 0]];
  }));
  const serie = t.mcp.serie_15min.slice(-32);
  const max = Math.max(1, ...serie.map(b => b.claude + b.codex));
  $('histograma').replaceChildren(...serie.map(b => {
    const m = document.createElement('meter');
    m.max = max;
    m.value = b.claude + b.codex;
    m.title = hora.format(b.m) + ' · ' + bytes(m.value);
    m.setAttribute('aria-label', m.title);
    return m;
  }));
  const s = t.servidor.total;
  dados('http', [['Recebido', bytes(s.entrada)], ['Enviado', bytes(s.saida)], ['Requisições', numero.format(s.pedidos)], ['Notas no disco', bytes(t.vault_bytes)]]);
}

function pedirLogin() {
  if (android) {
    alerta('Pareamento USB indisponível. Verifique o servidor e o cabo.');
    return;
  }
  precisaLogin = true;
  if (!$('entrada').open) $('entrada').showModal();
}

async function atualizar() {
  if (atualizando || document.hidden || precisaLogin) return;
  atualizando = true;
  const ciclo = geracao;
  try {
    const vivo = await api('vivo?desde=' + desde);
    if (ciclo !== geracao) return;
    if (grafo?.versao !== vivo.grafo_versao) {
      const novo = await api('grafo');
      if (ciclo !== geracao) return;
      atualizarGrafo(novo);
    }
    atualizarVivo(vivo);
    alerta('');
  } catch (e) {
    if (ciclo !== geracao) return;
    conexao('Sem conexão');
    for (const [id, avatar] of Object.entries(avatares)) {
      avatar.definir('offline');
      $(id + '-estado').textContent = 'Sem conexão';
      $(id + '-fala').textContent = '';
    }
    if (e.status === 401) pedirLogin();
    else alerta(android
      ? contaAndroid ? 'Confira se o conector está aberto e o celular continua na mesma rede do PC.'
        : 'Reconecte o cabo USB e verifique o servidor no computador.'
      : 'Servidor local indisponível. Tentando reconectar.');
  } finally { atualizando = false; }
}

$('entrada').addEventListener('cancel', e => e.preventDefault());
$('login').onsubmit = async e => {
  e.preventDefault();
  const chave = $('chave');
  const enviar = e.currentTarget.querySelector('button');
  enviar.disabled = true;
  $('erro-login').textContent = '';
  try {
    await api('entrar', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: chave.value }) });
    chave.value = '';
    $('entrada').close();
    precisaLogin = false;
    atualizar();
  } catch (erro) {
    $('erro-login').textContent = erro.status === 401 ? 'Chave incorreta.' : erro.status === 429 ? 'Aguarde antes de tentar novamente.' : 'Falha na conexão.';
  } finally { enviar.disabled = false; }
};

$('sair').hidden = android;
if (android) {
  api('app-modo').then(modo => {
    contaAndroid = modo.conta;
    $('conta-android').hidden = !contaAndroid;
  }).catch(() => {});
  $('conta-android').onclick = () => api('app-ajustes').catch(() => alerta('Não foi possível abrir a conta.'));
}
$('sair').onclick = async () => {
  precisaLogin = true;
  geracao++;
  try {
    await api('sair', { method: 'POST' });
    location.reload();
  } catch (_) { precisaLogin = false; alerta('Falha ao encerrar a sessão.'); }
};
document.addEventListener('visibilitychange', () => { if (!document.hidden) atualizar(); });
timer = setInterval(atualizar, 3000);
window.addEventListener('pagehide', () => clearInterval(timer));
window.addEventListener('pageshow', e => { if (e.persisted) timer = setInterval(atualizar, 3000); });
atualizar();
