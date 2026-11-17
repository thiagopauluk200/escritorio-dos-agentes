'use strict';
/*
 * Personagens dos agentes (SVG + animações CSS em estilo.css).
 *
 * CLAUDE — curioso, cuidadoso, gentil e reflexivo. Um "mochi" coral com uma faísca na cabeça
 *          e cachecol creme. Trabalha lendo e anotando num livro; descansa tomando chá.
 * CODEX  — pragmático, rápido e direto, engenheiro de terminal. Um robô compacto com visor
 *          verde-água. Trabalha digitando num teclado com código subindo; descansa com café.
 *
 * Estados (vindos dos registros reais das sessões): trabalhando · tokens (aguardando/recebendo
 * tokens do modelo) · aguardando_voce · descansando · dormindo · offline.
 */
(function (global) {
  const CLAUDE = `
<svg class="p p-claude" viewBox="0 0 200 220" role="img" aria-label="Claude">
  <ellipse class="sombra" cx="100" cy="204" rx="52" ry="8" fill="#000" opacity=".35"/>
  <g class="fichas">
    <circle cx="70" cy="20" r="4" fill="#ffd2b8"/><circle cx="104" cy="8" r="3" fill="#ffe7d9"/>
    <circle cx="132" cy="24" r="4.5" fill="#ffc4a3"/><circle cx="88" cy="-6" r="3.5" fill="#fff"/>
    <circle cx="120" cy="-2" r="3" fill="#ffd2b8"/>
  </g>
  <g class="corpo">
    <g class="faisca">
      <path d="M100 18 L104 34 L118 30 L107 40 L116 52 L102 45 L100 60 L98 45 L84 52 L93 40 L82 30 L96 34 Z"
            fill="#ffb08f" stroke="#c45f3f" stroke-width="2" stroke-linejoin="round"/>
    </g>
    <path class="bolha" d="M100 52 C150 52 172 92 170 132 C168 176 140 198 100 198 C60 198 32 176 30 132 C28 92 50 52 100 52 Z"
          fill="#D97757"/>
    <path d="M100 112 C134 112 150 140 148 164 C146 186 126 194 100 194 C74 194 54 186 52 164 C50 140 66 112 100 112 Z"
          fill="#F0A486" opacity=".55"/>
    <g class="olhos">
      <g class="olho-aberto">
        <ellipse cx="80" cy="112" rx="9" ry="12" fill="#2b1a14"/><ellipse cx="120" cy="112" rx="9" ry="12" fill="#2b1a14"/>
        <circle cx="83" cy="107" r="3.4" fill="#fff"/><circle cx="123" cy="107" r="3.4" fill="#fff"/>
      </g>
      <g class="olho-fechado" fill="none" stroke="#2b1a14" stroke-width="3.5" stroke-linecap="round">
        <path d="M71 114 Q80 121 89 114"/><path d="M111 114 Q120 121 129 114"/>
      </g>
    </g>
    <ellipse cx="66" cy="132" rx="9" ry="5" fill="#ff8f86" opacity=".55"/>
    <ellipse cx="134" cy="132" rx="9" ry="5" fill="#ff8f86" opacity=".55"/>
    <path class="boca" d="M92 134 Q100 141 108 134" fill="none" stroke="#2b1a14" stroke-width="3" stroke-linecap="round"/>
    <path d="M52 150 Q100 176 148 150 L150 162 Q100 190 50 162 Z" fill="#f6ead8"/>
    <path d="M128 160 L140 196 L126 194 L120 164 Z" fill="#efdcc2"/>
    <g class="braco-e"><path d="M40 140 Q20 150 26 170" fill="none" stroke="#c4623f" stroke-width="13" stroke-linecap="round"/></g>
    <g class="braco-d"><path d="M160 140 Q180 150 174 170" fill="none" stroke="#c4623f" stroke-width="13" stroke-linecap="round"/></g>
  </g>
  <g class="prop prop-trabalhando">
    <g class="livro">
      <path d="M48 168 L100 160 L152 168 L152 196 L100 188 L48 196 Z" fill="#3b5b8f" stroke="#1f3253" stroke-width="2"/>
      <path d="M54 168 L100 162 L100 186 L54 192 Z" fill="#f8f1e3"/>
      <path class="pagina" d="M100 162 L146 168 L146 192 L100 186 Z" fill="#fffaf0"/>
      <g stroke="#9aa6bd" stroke-width="1.6"><path d="M60 172 L94 168"/><path d="M60 178 L94 174"/><path d="M60 184 L86 181"/></g>
    </g>
    <g class="pena"><path d="M150 150 L172 120 L176 124 Z" fill="#fff" stroke="#bbb"/><path d="M150 150 L156 146" stroke="#333" stroke-width="2"/></g>
  </g>
  <g class="prop prop-tokens">
    <circle class="anel" cx="100" cy="36" r="30" fill="none" stroke="#ffd2b8" stroke-width="2" stroke-dasharray="4 7"/>
  </g>
  <g class="prop prop-aguardando_voce">
    <g class="balao">
      <path d="M140 30 h46 a10 10 0 0 1 10 10 v24 a10 10 0 0 1 -10 10 h-30 l-10 10 l2 -10 h-8 a10 10 0 0 1 -10 -10 v-24 a10 10 0 0 1 10 -10 Z" fill="#fff"/>
      <text x="163" y="62" text-anchor="middle" font-size="26" font-weight="700" fill="#D97757">?</text>
    </g>
  </g>
  <g class="prop prop-descansando">
    <g class="xicara">
      <path d="M118 168 h32 v12 a16 16 0 0 1 -32 0 Z" fill="#f6ead8" stroke="#8b6a4f" stroke-width="2"/>
      <path d="M150 172 a7 7 0 0 1 0 12" fill="none" stroke="#8b6a4f" stroke-width="3"/>
      <g class="vapor" fill="none" stroke="#ffffff" stroke-width="2.5" stroke-linecap="round" opacity=".7">
        <path d="M126 160 q-5 -8 0 -16 q5 -8 0 -16"/><path d="M140 160 q-5 -8 0 -16 q5 -8 0 -16"/>
      </g>
    </g>
  </g>
  <g class="prop prop-dormindo">
    <g class="zzz" fill="#cfe1ff" font-weight="800">
      <text x="142" y="70" font-size="18">z</text><text x="156" y="52" font-size="22">z</text><text x="172" y="30" font-size="28">Z</text>
    </g>
  </g>
</svg>`;

  const CODEX = `
<svg class="p p-codex" viewBox="0 0 200 220" role="img" aria-label="Codex">
  <ellipse class="sombra" cx="100" cy="204" rx="50" ry="8" fill="#000" opacity=".35"/>
  <g class="fichas">
    <rect x="66" y="10" width="7" height="7" fill="#7dffd9"/><rect x="100" y="-4" width="6" height="6" fill="#c9fff0"/>
    <rect x="130" y="16" width="8" height="8" fill="#19C39C"/><rect x="84" y="-14" width="6" height="6" fill="#fff"/>
    <rect x="118" y="-10" width="5" height="5" fill="#7dffd9"/>
  </g>
  <g class="corpo">
    <g class="antena">
      <path d="M100 40 V22" stroke="#7f8d99" stroke-width="4" stroke-linecap="round"/>
      <circle class="led" cx="100" cy="18" r="6" fill="#19C39C"/>
    </g>
    <rect x="52" y="40" width="96" height="74" rx="18" fill="#2a3a44" stroke="#101a20" stroke-width="3"/>
    <rect x="44" y="64" width="10" height="24" rx="4" fill="#3c4f5b"/><rect x="146" y="64" width="10" height="24" rx="4" fill="#3c4f5b"/>
    <rect class="visor" x="62" y="54" width="76" height="44" rx="10" fill="#062a24"/>
    <g class="olhos">
      <g class="olho-aberto" fill="#19C39C">
        <rect x="78" y="66" width="12" height="16" rx="2"/><rect x="110" y="66" width="12" height="16" rx="2"/>
      </g>
      <g class="olho-fechado" fill="#19C39C"><rect x="76" y="76" width="16" height="4" rx="2"/><rect x="108" y="76" width="16" height="4" rx="2"/></g>
    </g>
    <g class="visor-carga"><rect x="70" y="88" width="60" height="5" rx="2.5" fill="#0d4a3e"/>
      <rect class="barra" x="70" y="88" width="60" height="5" rx="2.5" fill="#19C39C"/></g>
    <g class="visor-cursor"><text x="100" y="94" text-anchor="middle" font-family="monospace" font-size="11" fill="#7dffd9">&gt;_</text></g>
    <rect x="64" y="118" width="72" height="56" rx="12" fill="#33464f" stroke="#101a20" stroke-width="3"/>
    <rect x="76" y="128" width="48" height="20" rx="4" fill="#0f1c22"/>
    <circle class="luz l1" cx="86" cy="138" r="3.5" fill="#19C39C"/><circle class="luz l2" cx="100" cy="138" r="3.5" fill="#ffc861"/>
    <circle class="luz l3" cx="114" cy="138" r="3.5" fill="#5aa8ff"/>
    <rect x="80" y="156" width="40" height="5" rx="2.5" fill="#22323a"/>
    <g class="perna-e"><rect x="74" y="172" width="18" height="26" rx="6" fill="#2a3a44"/></g>
    <g class="perna-d"><rect x="108" y="172" width="18" height="26" rx="6" fill="#2a3a44"/></g>
    <g class="braco-e"><rect x="40" y="122" width="16" height="44" rx="8" fill="#3c4f5b" stroke="#101a20" stroke-width="2"/></g>
    <g class="braco-d"><rect x="144" y="122" width="16" height="44" rx="8" fill="#3c4f5b" stroke="#101a20" stroke-width="2"/></g>
  </g>
  <g class="prop prop-trabalhando">
    <g class="tela">
      <rect x="8" y="22" width="56" height="44" rx="6" fill="#071311" stroke="#19C39C" stroke-width="1.5"/>
      <g class="codigo" stroke-width="3" stroke-linecap="round">
        <path d="M16 34 h22" stroke="#19C39C"/><path d="M20 42 h30" stroke="#5aa8ff"/><path d="M20 50 h16" stroke="#ffc861"/>
        <path d="M16 58 h26" stroke="#19C39C"/><path d="M20 66 h20" stroke="#c792ea"/><path d="M16 74 h30" stroke="#5aa8ff"/>
      </g>
    </g>
    <g class="teclado"><rect x="50" y="176" width="100" height="16" rx="4" fill="#1b262c" stroke="#19C39C" stroke-width="1.5"/>
      <g fill="#3c4f5b"><rect x="58" y="180" width="10" height="4"/><rect x="72" y="180" width="10" height="4"/><rect x="86" y="180" width="10" height="4"/>
        <rect x="100" y="180" width="10" height="4"/><rect x="114" y="180" width="10" height="4"/><rect x="128" y="180" width="12" height="4"/>
        <rect x="66" y="186" width="66" height="3"/></g></g>
  </g>
  <g class="prop prop-aguardando_voce">
    <g class="balao">
      <path d="M140 30 h46 a10 10 0 0 1 10 10 v24 a10 10 0 0 1 -10 10 h-30 l-10 10 l2 -10 h-8 a10 10 0 0 1 -10 -10 v-24 a10 10 0 0 1 10 -10 Z" fill="#0f1c22" stroke="#19C39C" stroke-width="2"/>
      <text class="cursor" x="163" y="60" text-anchor="middle" font-family="monospace" font-size="18" font-weight="700" fill="#19C39C">$_</text>
    </g>
  </g>
  <g class="prop prop-descansando">
    <g class="caneca">
      <rect x="150" y="164" width="24" height="26" rx="4" fill="#5aa8ff" stroke="#1d3d66" stroke-width="2"/>
      <path d="M174 170 a7 7 0 0 1 0 14" fill="none" stroke="#1d3d66" stroke-width="3"/>
      <g class="vapor" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="round" opacity=".7">
        <path d="M156 158 q-5 -8 0 -16 q5 -8 0 -16"/><path d="M168 158 q-5 -8 0 -16 q5 -8 0 -16"/>
      </g>
    </g>
  </g>
  <g class="prop prop-dormindo">
    <g class="zzz" fill="#7dffd9" font-family="monospace" font-weight="800">
      <text x="142" y="62" font-size="16">z</text><text x="156" y="44" font-size="20">z</text><text x="172" y="24" font-size="26">Z</text>
    </g>
  </g>
</svg>`;

  const ESTADOS = {
    trabalhando: 'Trabalhando',
    tokens: 'Aguardando tokens',
    limite: 'Limite de uso atingido',
    aguardando_voce: 'Aguardando você',
    descansando: 'Descansando',
    dormindo: 'Dormindo',
    offline: 'Offline',
  };

  function tempo(ms) {
    if (!ms) return 'há muito tempo';
    const s = Math.max(0, (Date.now() - ms) / 1000);
    if (s < 60) return 'há ' + Math.round(s) + ' s';
    if (s < 3600) return 'há ' + Math.round(s / 60) + ' min';
    if (s < 86400) return 'há ' + Math.round(s / 3600) + ' h';
    return 'há ' + Math.round(s / 86400) + ' d';
  }

  function nomeNota(id) {
    if (!id || id === 'vault') return 'o vault';
    return '«' + id.split('/').pop().replace(/\.md$/i, '') + '»';
  }

  /** Fala de acordo com a personalidade, sempre construída a partir dos dados reais. */
  function fala(agente, a, ultimoEvento) {
    if (!a || !a.instalado) return agente === 'claude' ? 'Não encontrei minhas sessões neste PC.' : 'Sessões não encontradas.';
    const s = a.sessao || {};
    const ativ = a.atividade || '';
    const tpm = Math.round(a.tokens_min || 0);
    const memoria = ativ.startsWith('memória') && ultimoEvento;
    if (a.estado === 'limite') {
      return s.renova
        ? 'Renovação indicada no registro: ' + new Date(s.renova).toLocaleString('pt-BR')
        : 'O registro indica um limite de uso. Horário de renovação indisponível.';
    }
    if (agente === 'claude') {
      switch (a.estado) {
        case 'trabalhando':
          if (memoria) return `Consultando a memória: ${nomeNota(ultimoEvento.no)}. Quero o contexto certo antes de seguir.`;
          return `Estou ${ativ || 'trabalhando'}. Com calma e cuidado, um passo de cada vez.`;
        case 'tokens': return `Pensando… ${tpm ? tpm.toLocaleString('pt-BR') + ' tokens/min chegando' : 'esperando a resposta do modelo'}.`;
        case 'aguardando_voce': return `Terminei por aqui${s.titulo ? ' («' + s.titulo + '»)' : ''}. Quando quiser, continuo com você.`;
        case 'descansando': return `Uma pausa para um chá. Última atividade ${tempo(s.ultimo)}.`;
        default: return `Zzz… nenhuma sessão ativa ${tempo(s.ultimo)}.`;
      }
    }
    switch (a.estado) {
      case 'trabalhando':
        if (memoria) return `Memória em uso: ${nomeNota(ultimoEvento.no)}.`;
        return `${ativ ? ativ[0].toUpperCase() + ativ.slice(1) : 'Executando'}. Já volto.`;
      case 'tokens': return `Gerando. ${tpm ? tpm.toLocaleString('pt-BR') + ' tok/min.' : 'Aguardando o modelo.'}`;
      case 'aguardando_voce': return 'Pronto. Próximo comando?';
      case 'descansando': return `Ocioso. Café. Último ciclo ${tempo(s.ultimo)}.`;
      default: return `Em espera ${tempo(s.ultimo)}.`;
    }
  }

  function montar(el, agente) {
    el.innerHTML = agente === 'claude' ? CLAUDE : CODEX;
    const svg = el.querySelector('svg');
    return {
      definir(estado, energia) {
        const cls = 'p p-' + agente + ' est-' + (estado || 'dormindo');
        if (svg.getAttribute('class') !== cls) svg.setAttribute('class', cls);
        svg.dataset.energia = energia > 2000 ? 'alta' : energia > 300 ? 'media' : 'baixa';
      },
    };
  }

  global.Personagens = { montar, fala, ESTADOS, tempo };
})(window);
