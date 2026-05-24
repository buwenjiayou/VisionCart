let currentRecognition = null;
let currentProducts = [];

const apiBaseInput = document.querySelector("#apiBase");
const authTokenInput = document.querySelector("#authToken");
const imageInput = document.querySelector("#imageInput");
const healthBox = document.querySelector("#healthBox");
const attributesBox = document.querySelector("#attributes");
const suggestionsBox = document.querySelector("#suggestions");
const productsBox = document.querySelector("#products");
const statsBox = document.querySelector("#stats");

document.querySelector("#healthBtn").addEventListener("click", checkHealth);
document.querySelector("#recognizeBtn").addEventListener("click", recognize);
document.querySelector("#nlpBtn").addEventListener("click", parseNlp);

render();

async function checkHealth() {
  try {
    const data = await api("/api/v1/health");
    healthBox.textContent = JSON.stringify(data, null, 2);
  } catch (error) {
    healthBox.textContent = "后端检查失败：\n" + error.message;
  }
}

async function recognize() {
  const file = imageInput.files?.[0];
  if (!file) {
    healthBox.textContent = "请先选择一张商品图片。";
    return;
  }

  const form = new FormData();
  form.append("image", file);
  try {
    const result = await api("/api/v1/recognition/analyze", {
      method: "POST",
      body: form,
      skipJsonContentType: true
    });
    currentRecognition = result.data;
    await search();
    render();
  } catch (error) {
    healthBox.textContent = "识别失败：\n" + error.message;
  }
}

async function parseNlp() {
  if (!currentRecognition) {
    healthBox.textContent = "请先完成图片识别。";
    return;
  }

  const input = document.querySelector("#nlpInput").value;
  try {
    const parsed = await api("/api/v1/nlp/parse", {
      method: "POST",
      body: JSON.stringify({
        session_id: currentRecognition.session_id,
        user_input: input,
        context: {
          product_name: currentRecognition.keywords?.join(" ") || "",
          category: currentRecognition.category?.level3 || currentRecognition.category?.level2 || ""
        }
      })
    });
    await search(parsed.data.filter);
    render();
  } catch (error) {
    healthBox.textContent = "语义筛选失败：\n" + error.message;
  }
}

async function search(filter = {}) {
  const attributes = Object.fromEntries(
    Object.entries(currentRecognition.attributes || {}).map(([key, value]) => [key, value.value])
  );
  const result = await api("/api/v1/search/products", {
    method: "POST",
    body: JSON.stringify({
      session_id: currentRecognition.session_id,
      attributes,
      filter,
      page: 1,
      page_size: 20,
      client_type: "web"
    })
  });
  currentProducts = result.data.products || [];
  renderStats(result.data.platform_stats || []);
}

async function api(path, options = {}) {
  const headers = options.skipJsonContentType ? {} : { "Content-Type": "application/json" };
  const token = authTokenInput.value.trim();
  if (token) {
    headers.Authorization = token.startsWith("Bearer ") ? token : `Bearer ${token}`;
  }

  const response = await fetch(apiBaseInput.value.replace(/\/$/, "") + path, {
    ...options,
    headers: { ...headers, ...(options.headers || {}) }
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok || (data && data.code && data.code !== 200)) {
    throw new Error(data?.message || `HTTP ${response.status}`);
  }
  return data;
}

function render() {
  if (!currentRecognition) {
    attributesBox.innerHTML = '<p class="empty">等待上传图片识别。</p>';
    suggestionsBox.innerHTML = "";
    productsBox.innerHTML = '<p class="empty">识别完成后会显示真实商品结果。</p>';
    statsBox.innerHTML = "";
    return;
  }

  attributesBox.innerHTML = Object.entries(currentRecognition.attributes || {})
    .map(([key, value]) => `<div class="product-row"><span>${key}</span><strong>${value.value}</strong><small>${Math.round(value.confidence * 100)}%</small></div>`)
    .join("");

  renderProducts();
}

function renderProducts() {
  productsBox.innerHTML = currentProducts
    .map((item) => `
      <article class="product-row">
        <div>
          <strong>${item.title}</strong>
          <p>${item.platform} · ${item.shop_name || "未知店铺"} · ${item.self_operated ? "官方/自营" : "店铺"}</p>
          <span class="tag">评分 ${item.rating}</span>
          <span class="tag">销量 ${item.sales}</span>
        </div>
        <div class="price">¥${item.price}</div>
      </article>
    `)
    .join("") || '<p class="empty">真实平台暂未返回商品，请检查平台 API 配置或关键词。</p>';
}

function renderStats(stats = []) {
  statsBox.innerHTML = stats
    .map((item) => `<div class="product-row"><strong>${item.platform}</strong><span>最低 ¥${item.min_price}</span><span>均价 ¥${item.avg_price}</span></div>`)
    .join("");
}
