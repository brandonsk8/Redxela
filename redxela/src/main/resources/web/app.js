/* =====================================================================
   REDXela — lógica de la interfaz
   - Recibe el estado completo por Server-Sent Events (/api/stream).
   - Dibuja cada sección solo si su HTML cambió (evita parpadeos y
     no pierde el foco del teclado).
   - Envía las acciones del usuario a la API REST con fetch().
   ===================================================================== */
"use strict";

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

let catalogo = null;      // productos, tipos, niveles, políticas...
let estado = null;        // última fotografía recibida
let pestana = "operacion";
let loteMarcado = null;   // lote seleccionado en el mapa del almacén
let numClientes = 0;

const NOMBRE_EVENTO = { INFO: "Información", OK: "Éxito", AVISO: "Aviso", ERROR: "Error", IPC: "Comunicación", CONFLICTO: "Conflicto" };
const NOMBRE_TIPO = { GENERAL: "General", FRAGIL: "Frágil", PESADO: "Pesado", VOLUMINOSO: "Voluminoso", PERECEDERO: "Perecedero", ALTO_VALOR: "Alto valor" };

// ---------------------------------------------------------------- utilidades
function esc(v) {
  return String(v ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
function min(v) { return (v ?? 0).toFixed(v >= 100 ? 0 : 1) + " min"; }
function pct(v) { return Math.round(v ?? 0) + " %"; }
function seg(ms) { return (ms / 1000).toFixed(1) + " s"; }
function nivel(n, nombre) { return `<span class="nivel" data-n="${n}">${esc(nombre)}</span>`; }

/** Reemplaza el contenido solo si cambió. */
function pintar(el, html) {
  if (typeof el === "string") el = $(el);
  if (el && el.__html !== html) { el.innerHTML = html; el.__html = html; }
}

async function api(metodo, ruta, cuerpo) {
  const r = await fetch(ruta, {
    method: metodo,
    headers: cuerpo ? { "Content-Type": "application/json" } : {},
    body: cuerpo ? JSON.stringify(cuerpo) : undefined,
  });
  let datos = null;
  try { datos = await r.json(); } catch { /* respuesta sin cuerpo */ }
  if (!r.ok) throw new Error((datos && datos.error) || `Error ${r.status}`);
  return datos;
}

let temporizadorToast;
function toast(msg, error = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.toggle("error", error);
  t.classList.add("visible");
  clearTimeout(temporizadorToast);
  temporizadorToast = setTimeout(() => t.classList.remove("visible"), 3200);
}

function mensaje(form, texto, error = false) {
  const m = $(".mensaje", form);
  m.textContent = texto;
  m.className = "mensaje " + (error ? "error" : "ok");
}

// ---------------------------------------------------------------- pestañas
function irA(nombre) {
  pestana = nombre;
  $$(".pestanas button").forEach(b => b.setAttribute("aria-selected", String(b.dataset.tab === nombre)));
  $$(".panel").forEach(p => { p.hidden = p.id !== "tab-" + nombre; });
  if (estado) render();
}
$$(".pestanas button").forEach(b => b.addEventListener("click", () => irA(b.dataset.tab)));

// ---------------------------------------------------------------- catálogo y formularios
async function cargarCatalogo() {
  catalogo = await api("GET", "/api/catalogo");
  $("#cfg-politica").innerHTML = catalogo.politicas.map(p => `<option value="${p.id}">${esc(p.nombre)}</option>`).join("");
  $$(".sel-nivel").forEach(s => {
    s.innerHTML = catalogo.niveles.map(n => `<option value="${n.numero}" ${n.numero === 3 ? "selected" : ""}>${n.numero} · ${esc(n.nombre)} — ${esc(n.descripcion)}</option>`).join("");
  });
  const opcionesProducto = catalogo.productos.map(p => `<option value="${p.id}">${esc(p.nombre)} (${esc(p.tipoNombre)})</option>`).join("");
  $("#sel-producto-merc").innerHTML = opcionesProducto;
  $("#esc-producto").innerHTML = `<option value="">El de más existencias</option>` +
    catalogo.productos.filter(p => p.tipo === "ALTO_VALOR").map(p => `<option value="${p.id}">${esc(p.nombre)}</option>`).join("");
  llenarClientes(catalogo.clientes);
  $("#lineas").innerHTML = "";
  agregarLinea();
  pintarTipos();
}

function llenarClientes(clientes) {
  const sel = $("#sel-cliente");
  const actual = sel.value;
  sel.innerHTML = clientes.map(c => `<option value="${c.id}">${esc(c.nombre)} — nivel ${c.nivel} ${esc(c.nivelNombre)}</option>`).join("");
  if (actual && clientes.some(c => c.id === actual)) sel.value = actual;
  numClientes = clientes.length;
}

async function refrescarClientes() {
  try { llenarClientes(await api("GET", "/api/clientes")); } catch { /* se reintentará */ }
}

function agregarLinea() {
  const div = document.createElement("div");
  div.className = "linea";
  div.innerHTML = `<select aria-label="Producto">${catalogo.productos.map(p => `<option value="${p.id}">${esc(p.nombre)} · ${esc(p.tipoNombre)}</option>`).join("")}</select>
    <input type="number" min="1" max="200" value="2" aria-label="Cantidad">
    <button type="button" class="quitar" aria-label="Quitar producto">✕</button>`;
  $(".quitar", div).addEventListener("click", () => { if ($$("#lineas .linea").length > 1) div.remove(); resumirPedido(); });
  div.addEventListener("input", resumirPedido);
  $("#lineas").appendChild(div);
  resumirPedido();
}

/** Muestra qué recursos necesitará el pedido según sus tipos de mercancía. */
function resumirPedido() {
  if (!catalogo) return;
  const lineas = $$("#lineas .linea");
  let unidades = 0;
  const tipos = new Set();
  lineas.forEach(l => {
    unidades += Number($("input", l).value) || 0;
    const p = catalogo.productos.find(x => x.id === $("select", l).value);
    if (p) tipos.add(p.tipo);
  });
  const req = {};
  tipos.forEach(t => {
    const tipo = catalogo.tipos.find(x => x.id === t);
    Object.entries(tipo.recursos).forEach(([r, c]) => { req[r] = Math.max(req[r] || 0, c); });
  });
  const nombres = Object.fromEntries(catalogo.recursos.map(r => [r.id, r.corto]));
  const lista = catalogo.recursos.filter(r => req[r.id]).map(r => `${req[r.id]} ${nombres[r.id]}`).join(", ");
  $("#resumen-pedido").textContent = `${unidades} unidades · necesitará: ${lista || "—"}`;
}

function pintarTipos() {
  const nombres = Object.fromEntries(catalogo.recursos.map(r => [r.id, r.corto]));
  $("#tipos-mercancia").innerHTML = catalogo.tipos.map(t => `
    <div class="tipo"><b>${esc(t.nombre)}</b>
      <p>${esc(t.descripcion)}</p>
      <div class="chips">${catalogo.recursos.filter(r => t.recursos[r.id]).map(r =>
        `<span class="etiqueta recursos">${t.recursos[r.id]} ${esc(nombres[r.id])}</span>`).join("")}</div>
    </div>`).join("");
}

$$("input[name=tipoCliente]").forEach(r => r.addEventListener("change", () => {
  const nuevo = $("input[name=tipoCliente]:checked").value === "nuevo";
  $("#bloque-nuevo").hidden = !nuevo;
  $("#bloque-existente").hidden = nuevo;
}));
$("#agregar-linea").addEventListener("click", agregarLinea);

$("#form-pedido").addEventListener("submit", async e => {
  e.preventDefault();
  const f = e.target;
  const lineas = $$("#lineas .linea").map(l => ({ productoId: $("select", l).value, cantidad: Number($("input", l).value) }));
  const cuerpo = { lineas };
  if ($("input[name=tipoCliente]:checked").value === "nuevo") {
    cuerpo.nuevoCliente = { nombre: f.nombre.value, correo: f.correo.value, nivel: Number(f.nivel.value) };
  } else {
    cuerpo.clienteId = f.clienteId.value;
  }
  try {
    const r = await api("POST", "/api/pedidos", cuerpo);
    mensaje(f, `Pedido ${r.id} registrado (${r.cliente}, ${r.nivel}, ${r.unidades} unidades). Ya está en la lista de espera.`);
    toast(`Pedido ${r.id} en la lista de espera`);
    if (cuerpo.nuevoCliente) {
      f.nombre.value = ""; f.correo.value = "";
      await refrescarClientes();
      $("#sel-cliente").value = r.clienteId;
      $("input[name=tipoCliente][value=existente]").checked = true;
      $("#bloque-nuevo").hidden = true; $("#bloque-existente").hidden = false;
    }
  } catch (err) { mensaje(f, err.message, true); }
});

$("#form-mercaderia").addEventListener("submit", async e => {
  e.preventDefault();
  const f = e.target;
  try {
    const r = await api("POST", "/api/mercaderia", { productoId: f.productoId.value, cantidad: Number(f.cantidad.value), anden: Number(f.anden.value) });
    mensaje(f, `Camión ${r.id} en el patio: ${r.cantidad} × ${r.producto}, andén ${r.anden}.`);
    toast(`Camión ${r.id} llegó a Recepción`);
  } catch (err) { mensaje(f, err.message, true); }
});

$("#btn-rafaga").addEventListener("click", async () => {
  try {
    const r = await api("POST", "/api/mercaderia/rafaga", { camiones: Number($("#rafaga-n").value) });
    toast(`${r.camiones.length} camiones llegaron al patio`);
  } catch (err) { toast(err.message, true); }
});

// ---------------------------------------------------------------- configuración
function enviarConfig(cambio) {
  api("POST", "/api/config", cambio).catch(err => toast(err.message, true));
}
$("#cfg-politica").addEventListener("change", e => { enviarConfig({ politica: e.target.value }); toast("Política cambiada"); });
$("#cfg-prevencion").addEventListener("change", e => enviarConfig({ prevencion: e.target.checked }));
$("#cfg-generador").addEventListener("change", e => enviarConfig({ generador: e.target.checked }));
$("#cfg-reposicion").addEventListener("change", e => enviarConfig({ reposicion: e.target.checked }));
$("#cfg-tasa").addEventListener("change", e => enviarConfig({ tasa: Number(e.target.value) }));
$("#cfg-velocidad").addEventListener("change", e => enviarConfig({ factorTiempo: Number(e.target.value) }));
$("#cfg-retardo").addEventListener("change", e => enviarConfig({ retardoInventario: Number(e.target.value) }));

/** Refleja la configuración del servidor sin pisar un control que el usuario está editando. */
function sincronizarConfig(c) {
  const fijar = (sel, prop, valor) => { const el = $(sel); if (document.activeElement !== el && el[prop] !== valor) el[prop] = valor; };
  fijar("#cfg-politica", "value", c.politica);
  fijar("#cfg-prevencion", "checked", c.prevencion);
  fijar("#cfg-generador", "checked", c.generador);
  fijar("#cfg-reposicion", "checked", c.reposicion);
  fijar("#cfg-tasa", "value", String(Math.round(c.tasa)));
  fijar("#cfg-velocidad", "value", String(c.factorTiempo));
  fijar("#cfg-retardo", "value", String(c.retardoInventario));
}

// ---------------------------------------------------------------- conflictos
$("#btn-provocar").addEventListener("click", async () => {
  const b = $("#btn-provocar");
  b.disabled = true;
  try {
    const r = await api("POST", "/api/conflictos/provocar", { anden: Number($("#esc-anden").value), productoId: $("#esc-producto").value || null });
    $("#msg-provocar").textContent = `Iniciado: camión ${r.camion} contra pedido ${r.pedido}. En unos segundos aparece la alerta.`;
    $("#msg-provocar").className = "mensaje ok";
  } catch (err) {
    $("#msg-provocar").textContent = err.message;
    $("#msg-provocar").className = "mensaje error";
  } finally { b.disabled = false; }
});

document.addEventListener("click", async e => {
  const b = e.target.closest("[data-ganador]");
  if (!b) return;
  b.disabled = true;
  try {
    await api("POST", "/api/conflictos/resolver", { conflictoId: b.dataset.conflicto, ganadorId: b.dataset.ganador });
    toast(`Continúa ${b.dataset.ganador}; la otra operación regresa a su cola`);
  } catch (err) { toast(err.message, true); b.disabled = false; }
});

/** Grafo de asignación de recursos del ciclo: operaciones (rectángulos) y recursos (círculos). */
function svgCiclo(ciclo) {
  const n = ciclo.length;
  const W = 420, H = 300, cx = W / 2, cy = H / 2, R = 108;
  const nodos = [];
  ciclo.forEach((e, i) => {
    nodos.push({ tipo: "op", texto: e.id, sub: e.tipo === "RECEPCION" ? "Recepción" : (e.nivel || "Pedido") });
    nodos.push({ tipo: "rec", texto: e.espera });
  });
  const total = nodos.length;
  nodos.forEach((nd, k) => {
    const a = -Math.PI / 2 + (k * 2 * Math.PI) / total + (n === 2 ? Math.PI / 4 : 0);
    nd.x = cx + R * 1.35 * Math.cos(a); nd.y = cy + R * Math.sin(a);
  });
  const flecha = (a, b, estilo) => {
    const dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy);
    const ra = a.tipo === "op" ? 40 : 30, rb = b.tipo === "op" ? 44 : 34;
    const x1 = a.x + dx * ra / d, y1 = a.y + dy * ra / d, x2 = b.x - dx * rb / d, y2 = b.y - dy * rb / d;
    return `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${estilo.c}" stroke-width="2.5" ${estilo.dash ? 'stroke-dasharray="6 4"' : ""} marker-end="url(#m-${estilo.id})"/>`;
  };
  let lineas = "";
  for (let i = 0; i < n; i++) {
    const op = nodos[2 * i], rec = nodos[2 * i + 1], sig = nodos[(2 * i + 2) % total];
    lineas += flecha(op, rec, { c: "#b93226", dash: true, id: "e" });   // op espera recurso
    lineas += flecha(rec, sig, { c: "#2f6690", dash: false, id: "a" });  // recurso asignado a la siguiente
  }
  const figuras = nodos.map(nd => nd.tipo === "op"
    ? `<rect x="${nd.x - 46}" y="${nd.y - 24}" width="92" height="48" rx="3" fill="#fff" stroke="#22272c" stroke-width="2"/>
       <text x="${nd.x}" y="${nd.y - 3}" text-anchor="middle" font-size="14" font-weight="600" fill="#22272c">${esc(nd.texto)}</text>
       <text x="${nd.x}" y="${nd.y + 14}" text-anchor="middle" font-size="11" fill="#59626b">${esc(nd.sub)}</text>`
    : `<circle cx="${nd.x}" cy="${nd.y}" r="32" fill="#fbf0c8" stroke="#8a6800" stroke-width="2"/>
       <text x="${nd.x}" y="${nd.y + 4}" text-anchor="middle" font-size="12" font-weight="600" fill="#22272c">${esc(nd.texto)}</text>`).join("");
  return `<svg class="ciclo-svg" viewBox="0 0 ${W} ${H}" role="img" aria-label="Grafo de espera del interbloqueo">
    <defs>
      <marker id="m-e" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#b93226"/></marker>
      <marker id="m-a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#2f6690"/></marker>
    </defs>
    ${lineas}${figuras}
    <g font-size="11" fill="#59626b"><line x1="10" y1="${H - 26}" x2="34" y2="${H - 26}" stroke="#b93226" stroke-width="2.5" stroke-dasharray="6 4"/><text x="40" y="${H - 22}">solicita (espera)</text>
    <line x1="10" y1="${H - 10}" x2="34" y2="${H - 10}" stroke="#2f6690" stroke-width="2.5"/><text x="40" y="${H - 6}">asignado a</text></g>
  </svg>`;
}

function pintarAlertaConflicto(conflictos) {
  const activos = conflictos.filter(c => c.estado === "ACTIVO");
  const caja = $("#alerta-conflicto");
  const badge = $("#badge-conflictos");
  badge.hidden = activos.length === 0;
  badge.textContent = activos.length;
  caja.hidden = activos.length === 0;
  if (!activos.length) { pintar(caja, ""); return; }
  const c = activos[activos.length - 1];
  const filas = c.ciclo.map(e => `<li><b>${esc(e.id)}</b> — ${esc(e.descripcion)}. Retiene: ${esc(e.retiene.join(", ") || "nada")}. Espera: <b>${esc(e.espera)}</b> (en manos de ${esc(e.duenoDeEspera)}).</li>`).join("");
  const botones = c.ciclo.map(e => `<button class="boton ganador" data-conflicto="${c.id}" data-ganador="${esc(e.id)}">Continuar con ${esc(e.id)}</button>`).join("");
  pintar(caja, `<div class="conflicto-cuerpo">
      <div>
        <h2>Interbloqueo ${esc(c.id)}: ${c.ciclo.length} operaciones detenidas</h2>
        <p class="explicacion">${esc(c.explicacion)}</p>
        <ul>${filas}</ul>
        <p class="ayuda">Elige qué operación conserva los recursos en disputa. Las demás liberan todo lo que tienen y vuelven a su cola: el pedido a la lista de espera, el camión al patio.</p>
        <div class="decision">${botones}</div>
      </div>
      <div>${svgCiclo(c.ciclo)}</div>
    </div>`);
}

function pintarHistorial(conflictos) {
  if (!conflictos.length) {
    pintar("#historial-conflictos", `<div class="vacio">Sin conflictos todavía. Usa “Provocar conflicto” o desactiva la prevención con carga alta.</div>`);
    return;
  }
  pintar("#historial-conflictos", conflictos.map(c => {
    const det = new Date(c.detectadoMs).toLocaleTimeString("es-GT");
    const res = c.estado === "RESUELTO"
      ? `<p>Resuelto a las ${new Date(c.resueltoMs).toLocaleTimeString("es-GT")}: ${c.ganador ? `continuó <b>${esc(c.ganador)}</b>.` : "el ciclo desapareció."}</p>`
      : `<p><span class="etiqueta bloqueo">Activo</span> esperando tu decisión.</p>`;
    return `<article class="conflicto-hist ${c.estado === "RESUELTO" ? "resuelto" : ""}">
      <b>${esc(c.id)}</b> · detectado a las ${det}
      <p>${esc(c.explicacion)}</p>${res}</article>`;
  }).join(""));
}

// ---------------------------------------------------------------- render principal
function render() {
  const e = estado;
  sincronizarConfig(e.config);
  if (e.numClientes !== numClientes) refrescarClientes();

  const est = e.estadisticas, g = est.global;
  const activos = e.conflictos.filter(c => c.estado === "ACTIVO").length;
  pintar("#indicadores", [
    [e.espera.length, "en lista de espera"],
    [e.operaciones.filter(o => o.tipo === "PEDIDO").length, "pedidos en proceso"],
    [g.completados, "pedidos completados"],
    [min(g.esperaPromedioMin), "espera promedio"],
    [pct(g.cumplimientoPct), "atendidos en plazo"],
    [e.ipc.patio.length, "camiones en el patio"],
    [activos, "interbloqueos activos", activos > 0],
  ].map(([v, t, alarma]) => `<div class="indicador ${alarma ? "alarma" : ""}"><b>${esc(v)}</b><span>${esc(t)}</span></div>`).join(""));

  pintarAlertaConflicto(e.conflictos);

  if (pestana === "operacion") pintarOperacion(e);
  if (pestana === "almacen") pintarAlmacen(e);
  if (pestana === "conflictos") pintarHistorial(e.conflictos);
  if (pestana === "estadisticas") pintarEstadisticas(est);
  if (pestana === "bitacora") pintarBitacora(e.bitacora);
}

function pintarOperacion(e) {
  // --- recursos como bahías de piso
  pintar("#recursos", e.recursos.map(r => `
    <div class="grupo-recurso">
      <h3>${esc(r.nombre)} <small>${r.disponibles} de ${r.capacidad} libres</small></h3>
      <div class="bahias">${r.unidades.map(u => {
        const espera = u.esperando.length ? `<span class="espera">Esperan: ${esc(u.esperando.join(", "))}</span>` : "";
        const clase = u.libre ? "" : `ocupada ${u.tipoDuenio === "RECEPCION" ? "recepcion" : ""}`;
        return `<div class="bahia ${clase} ${u.esperando.length && !u.libre ? "bloqueada" : ""}" title="${esc(u.etiqueta || "Libre")}">
          <span class="nombre">${esc(u.nombre)}</span>
          <span class="dueno">${u.libre ? "Libre" : esc(u.etiqueta)}</span>${espera}</div>`;
      }).join("")}</div>
    </div>`).join(""));

  // --- operaciones activas
  const ops = e.operaciones;
  $("#cuenta-proceso").textContent = `${ops.length} operaciones`;
  pintar("#en-proceso", ops.length ? ops.map(o => {
    const esperando = !!o.espera;
    if (o.tipo === "RECEPCION") {
      return `<div class="operacion recepcion ${esperando ? "esperando" : ""}">
        <div class="cabeza"><b>${esc(o.id)}</b><span class="etiqueta">Recepción · andén ${o.anden}</span></div>
        <div class="detalle">${o.cantidad} × ${esc(o.producto)}</div>
        <div class="fase">${esc(o.fase)}</div>
        <div class="pie-op"><span>Retiene: ${esc(o.retiene.join(", ") || "nada")}</span></div></div>`;
    }
    return `<div class="operacion ${esperando ? "esperando" : ""}">
      <div class="cabeza"><b>${esc(o.id)}</b>${nivel(o.nivel, o.nivelNombre)}</div>
      <div class="detalle">${esc(o.cliente)} · ${o.unidades} u. · ${esc(o.tipos.join(", "))}</div>
      <div class="fase">${esc(o.fase)}${o.espera ? ` — espera <b>${esc(o.espera)}</b>` : ""}</div>
      <div class="progreso" role="progressbar" aria-valuenow="${Math.round(o.progreso * 100)}" aria-valuemin="0" aria-valuemax="100"><i style="width:${(o.progreso * 100).toFixed(1)}%"></i></div>
      <div class="pie-op"><span>${Math.round(o.progreso * 100)} %</span><span>Restan ${seg(o.restanteMs)}</span></div>
      <div class="pie-op"><span>${esc(o.retiene.join(", "))}</span></div></div>`;
  }).join("") : `<div class="vacio">No hay operaciones en curso.</div>`);

  // --- lista de espera
  $("#cuenta-espera").textContent = `${e.espera.length} pedidos`;
  $("#criterio-espera").textContent = `Ordenada por ${e.config.politicaNombre}: ${e.config.politicaDescripcion} Puntaje = ${e.config.politicaCriterio}; menor puntaje se atiende antes.`;
  const sit = p => {
    if (p.estado === "ESPERANDO_STOCK") return `<span class="etiqueta stock">Sin stock</span>`;
    if (p.estado === "ESPERANDO_RECURSOS") return `<span class="etiqueta recursos">Sin recursos</span>`;
    if (p.estado === "BLOQUEADO") return `<span class="etiqueta bloqueo">Bloqueado</span>`;
    return `<span class="etiqueta">En espera</span>`;
  };
  pintar("#tabla-espera tbody", e.espera.length ? e.espera.slice(0, 120).map(p => `
    <tr class="${p.vencido ? "vencido" : ""} ${p.estado === "BLOQUEADO" ? "bloqueado" : ""}">
      <td>${p.posicion}</td><td><b>${esc(p.id)}</b>${p.reintentos ? ` <span class="etiqueta">reintento ${p.reintentos}</span>` : ""}</td>
      <td>${esc(p.cliente)}</td><td>${nivel(p.nivel, p.nivelNombre)}</td><td>${esc(p.tipos.join(", "))}</td>
      <td class="num">${p.unidades}</td>
      <td class="num">${min(p.esperaMin)}<span class="motivo">máx. ${p.esperaMaxMin} min${p.vencido ? " · vencido" : ""}</span></td>
      <td class="num">${formatoPuntaje(p.puntaje)}</td>
      <td>${sit(p)}<span class="motivo">${esc(p.motivo)}</span></td>
    </tr>`).join("") : `<tr><td colspan="9"><div class="vacio">La lista de espera está vacía: todos los pedidos están siendo atendidos.</div></td></tr>`);

  pintar("#tabla-completados tbody", e.completados.map(p => `
    <tr><td>${esc(p.id)}</td><td>${esc(p.cliente)}</td><td>${nivel(p.nivel, p.nivelNombre)}</td><td class="num">${p.unidades}</td>
    <td class="num">${min(p.esperaMin)}</td><td class="num">${min(p.procesoMin)}</td>
    <td>${p.enPlazo ? `<span class="etiqueta ok">En plazo</span>` : `<span class="etiqueta mal">Fuera de plazo</span>`}</td></tr>`).join(""));
}

/** En prioridad estricta el puntaje es nivel·10⁶ + minuto de llegada: se muestra como "nivel · minuto". */
function formatoPuntaje(v) {
  if (estado.config.politica === "PRIORIDAD_ESTRICTA") return Math.floor(v / 1e6) + " · " + (v % 1e6).toFixed(1);
  return v.toFixed(estado.config.politica === "MENOS_ARTICULOS" ? 0 : 2);
}

function pintarAlmacen(e) {
  const i = e.ipc;
  const ranuras = [];
  for (let k = 0; k < i.capacidad; k++) {
    const m = i.contenido[k];
    ranuras.push(m ? `<div class="ranura llena"><b>${esc(m.camion)}</b>${m.cantidad} × ${esc(m.producto)}</div>` : `<div class="ranura"></div>`);
  }
  const lleno = i.ocupados >= i.capacidad;
  pintar("#ipc", `
    <div class="area">
      <h3>Recepción (productor)</h3>
      <p class="estado-area">${i.recepcionActual ? `Descargando <b>${esc(i.recepcionActual.id)}</b>: ${esc(i.recepcionActual.fase)}` : "Sin camión en descarga"}</p>
      <b>Patio: ${i.patio.length} camiones</b>
      <ul>${i.patio.map(c => `<li>${esc(c.id)} · ${c.cantidad} × ${esc(c.producto)} · andén ${c.anden}</li>`).join("") || "<li>Vacío</li>"}</ul>
    </div>
    <div class="area">
      <h3>Buffer acotado</h3>
      <div class="medidor-buffer ${lleno ? "lleno" : ""}"><span><b>${i.ocupados} / ${i.capacidad}</b> ocupados${lleno ? " — lleno, Recepción espera" : ""}</span>
        <span>Enviados ${i.producidos} · Procesados ${i.consumidos} · Esperas por lleno ${i.esperasPorLleno}</span></div>
      <div class="buffer" style="grid-template-columns:repeat(${Math.min(i.capacidad, 8)}, minmax(0,1fr))">${ranuras.join("")}</div>
      <div class="semaforos"><span>espaciosLibres = ${i.capacidad - i.ocupados}</span><span>elementos = ${i.ocupados}</span><span>mutex = 1</span></div>
    </div>
    <div class="area">
      <h3>Inventario (consumidor)</h3>
      <p class="estado-area">${i.inventarioProcesando ? `Registrando ${i.inventarioProcesando.cantidad} × ${esc(i.inventarioProcesando.producto)} (${esc(i.inventarioProcesando.camion)})` : "Esperando avisos"}</p>
      <b>Pendientes de ubicar: ${i.pendientes.length}</b>
      <ul>${i.pendientes.map(p => `<li>${p.cantidad} × ${esc(p.producto)} (${esc(p.camion)})</li>`).join("") || "<li>Ninguno: todo cupo en el almacén</li>"}</ul>
    </div>`);

  // --- mapa del almacén por pasillo
  const a = e.almacen;
  $("#resumen-almacen").textContent = `${a.ubicacionesUsadas} de ${a.ubicacionesUsadas + a.ubicacionesLibres} ubicaciones ocupadas (${pct(a.ocupacionPct)}) · ${a.unidades} de ${a.capacidadTotal} unidades · fragmentación interna: ${a.fragmentacionInterna} espacios sin usar dentro de ubicaciones ocupadas. Cada ubicación guarda hasta ${catalogo.almacen.capacidadUbicacion} unidades de un solo lote.`;
  const porPasillo = {};
  a.celdas.forEach(c => { (porPasillo[c.c[0]] ||= []).push(c); });
  const cap = catalogo.almacen.capacidadUbicacion;
  const leyenda = `<div class="leyenda"><span>Parte clara = espacio libre dentro de la ubicación.</span>${Object.entries(NOMBRE_TIPO).map(([k, v]) => `<span><i class="t-${k}"></i>${v}</span>`).join("")}<span><i style="background:var(--concreto);border:1px solid #d3d7db"></i>Libre</span></div>`;
  pintar("#mapa-almacen", leyenda + Object.entries(porPasillo).map(([p, celdas]) => {
    // niveles de arriba (4) hacia abajo (1), como se ve un rack de frente
    const filas = [];
    for (let n = catalogo.almacen.niveles; n >= 1; n--) filas.push(...celdas.filter(c => Number(c.c.split("-")[1]) === n));
    return `<div class="pasillo"><h4>Pasillo ${p}</h4><div class="estantes">${filas.map(c => c.l
      ? `<div class="celda ocupada t-${c.t} ${loteMarcado === c.l ? "marcada" : ""}" data-lote="${c.l}" title="${esc(c.c)} · ${esc(c.l)} · ${c.u} × ${esc(c.n)}"><span class="vacia" style="height:${(1 - c.u / cap) * 100}%"></span><span class="cod">${esc(c.c)}</span></div>`
      : `<div class="celda" title="${esc(c.c)} libre">${esc(c.c)}</div>`).join("")}</div></div>`;
  }).join(""));

  pintarDetalleLote(e);

  pintar("#tabla-stock tbody", e.inventario.map(p => `
    <tr><td>${esc(p.nombre)}</td><td>${esc(p.tipo)}</td>
      <td class="num">${p.stock < 15 ? `<span class="etiqueta stock">${p.stock}</span>` : p.stock}</td>
      <td class="num">${p.lotes.length}</td></tr>`).join(""));
}

function pintarDetalleLote(e) {
  if (!loteMarcado) return;
  for (const p of e.inventario) {
    const l = p.lotes.find(x => x.id === loteMarcado);
    if (l) {
      pintar("#detalle-lote", `<b>Lote ${esc(l.id)}</b> · ${esc(p.nombre)} · quedan ${l.restantes} de ${l.inicial} unidades ·
        ${l.disperso ? "ubicaciones no contiguas" : "ubicaciones contiguas"}
        <ol>${l.ubicaciones.map(u => `<li>${esc(u.codigo)}: ${u.unidades} u.</li>`).join("")}</ol>`);
      return;
    }
  }
  pintar("#detalle-lote", `El lote ${esc(loteMarcado)} ya salió por completo del almacén.`);
}

$("#mapa-almacen").addEventListener("click", e => {
  const c = e.target.closest("[data-lote]");
  if (!c) return;
  loteMarcado = c.dataset.lote;
  if (estado) pintarAlmacen(estado);
});

function pintarEstadisticas(est) {
  const g = est.global;
  pintar("#estadisticas-generales", [
    [g.completados, "pedidos completados"],
    [est.generados, `pedidos recibidos (${est.manuales} manuales)`],
    [min(g.esperaPromedioMin), "espera promedio"],
    [min(g.procesoPromedioMin), "procesamiento promedio"],
    [min(g.esperaMaximaMin), "espera máxima"],
    [pct(g.cumplimientoPct), "atendidos dentro del plazo"],
    [est.throughputPorHora.toFixed(1), "pedidos por hora simulada"],
    [`${est.conflictosDetectados} / ${est.conflictosResueltos}`, "interbloqueos detectados / resueltos"],
    [est.devueltosPorConflicto, "pedidos revertidos por conflicto"],
    [`${est.camionesRecibidos} · ${est.unidadesRecibidas} u.`, "camiones recibidos"],
  ].map(([v, t]) => `<div class="cifra"><b>${esc(v)}</b><span>${esc(t)}</span></div>`).join(""));

  pintar("#uso-recursos", catalogo.recursos.map(r => {
    const v = est.usoRecursosPct[r.id] || 0;
    return `<div class="barra-uso"><span>${esc(r.nombre)}</span><span class="pista"><i class="${v > 85 ? "alto" : ""}" style="width:${v.toFixed(1)}%"></i></span><span class="num">${pct(v)}</span></div>`;
  }).join(""));

  pintar("#tabla-niveles tbody", catalogo.niveles.map(n => {
    const x = est.porNivel[n.numero];
    return `<tr><td>${nivel(n.numero, n.nombre)}</td>${x
      ? `<td class="num">${x.completados}</td><td class="num">${min(x.esperaPromedioMin)}</td><td class="num">${min(x.esperaMaximaMin)}</td><td class="num">${pct(x.cumplimientoPct)}</td>`
      : `<td colspan="4" class="num">sin datos</td>`}</tr>`;
  }).join(""));

  const pols = Object.values(est.porPolitica);
  pintar("#tabla-politicas tbody", pols.length ? pols.map(p => `
    <tr><td>${esc(p.nombre)}</td><td class="num">${p.completados}</td><td class="num">${min(p.esperaPromedioMin)}</td>
    <td class="num">${min(p.esperaMaximaMin)}</td><td class="num">${min(p.procesoPromedioMin)}</td><td class="num">${pct(p.cumplimientoPct)}</td></tr>`).join("")
    : `<tr><td colspan="6">Aún no hay pedidos completados.</td></tr>`);
}

$("#btn-reiniciar").addEventListener("click", async () => {
  try { await api("POST", "/api/estadisticas/reiniciar", {}); toast("Estadísticas en cero"); }
  catch (err) { toast(err.message, true); }
});

$("#form-comparar").addEventListener("submit", async e => {
  e.preventDefault();
  const f = e.target;
  const caja = $("#resultado-comparar");
  caja.innerHTML = `<p class="ayuda">Simulando…</p>`;
  try {
    const r = await api("POST", "/api/comparar", { pedidos: Number(f.pedidos.value), tasa: Number(f.tasa.value), semilla: Number(f.semilla.value) });
    caja.innerHTML = tablaComparacion(r);
  } catch (err) { caja.innerHTML = `<p class="mensaje error">${esc(err.message)}</p>`; }
});

function tablaComparacion(r) {
  const res = r.resultados;
  const mejorDe = (campo, menor) => {
    const vals = res.map(x => x[campo]);
    return menor ? Math.min(...vals) : Math.max(...vals);
  };
  const mejores = {
    esperaPromedioMin: mejorDe("esperaPromedioMin", true),
    esperaMaximaMin: mejorDe("esperaMaximaMin", true),
    cumplimientoPct: mejorDe("cumplimientoPct", false),
  };
  const celda = (x, campo, f) => `<td class="num ${Math.abs(x[campo] - mejores[campo]) < 1e-9 ? "mejor" : ""}">${f(x[campo])}</td>`;
  const nombre = id => res.find(x => x.politica === id).nombre;
  const filas = res.map(x => `<tr><td>${esc(x.nombre)}</td>${celda(x, "esperaPromedioMin", min)}${celda(x, "esperaMaximaMin", min)}${celda(x, "cumplimientoPct", pct)}
      ${[1, 2, 3, 4, 5].map(n => `<td class="num">${min(x.porNivel[n].esperaPromedioMin)}</td>`).join("")}
      <td class="num">${x.throughputPorHora.toFixed(1)}</td></tr>`).join("");
  return `<div class="conclusion">Con ${r.pedidos} pedidos a ${r.tasaPorHora} por hora: menor espera promedio con <b>${esc(nombre(r.mejorEspera))}</b>;
      más pedidos dentro de su plazo con <b>${esc(nombre(r.mejorCumplimiento))}</b>; menor espera máxima (menos inanición) con <b>${esc(nombre(r.mejorEsperaMaxima))}</b>.</div>
    <div class="tabla-scroll"><table class="tabla">
      <thead><tr><th>Política</th><th class="num">Espera prom.</th><th class="num">Espera máx.</th><th class="num">En plazo</th>
        ${[1, 2, 3, 4, 5].map(n => `<th class="num">Nivel ${n}</th>`).join("")}<th class="num">Pedidos/h</th></tr></thead>
      <tbody>${filas}</tbody></table></div>`;
}

function pintarBitacora(eventos) {
  const visibles = new Set($$("#filtros-bitacora input:checked").map(i => i.value));
  pintar("#bitacora", eventos.filter(ev => visibles.has(ev.nivel)).map(ev =>
    `<li class="ev-${ev.nivel}"><span class="hora">${esc(ev.hora)}</span><span class="tipo-ev">${NOMBRE_EVENTO[ev.nivel] || ev.nivel}</span><span>${esc(ev.mensaje)}</span></li>`).join(""));
}
$("#filtros-bitacora").addEventListener("change", () => estado && pintarBitacora(estado.bitacora));

// ---------------------------------------------------------------- conexión en tiempo real
function conectar() {
  const ind = $("#conexion");
  const fuente = new EventSource("/api/stream");
  fuente.onopen = () => { ind.textContent = "En vivo"; ind.className = "conexion viva"; };
  fuente.onmessage = ev => {
    try {
      estado = JSON.parse(ev.data);
      render();
    } catch (err) { console.error("Estado inválido", err); }
  };
  fuente.onerror = () => {
    ind.textContent = "Sin conexión con el servidor; reintentando…";
    ind.className = "conexion caida";
    // EventSource reintenta solo
  };
}

cargarCatalogo().then(conectar).catch(err => {
  $("#conexion").textContent = "No se pudo cargar el catálogo: " + err.message;
  $("#conexion").className = "conexion caida";
});
