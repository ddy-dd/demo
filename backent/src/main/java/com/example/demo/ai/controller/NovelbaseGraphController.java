package com.example.demo.ai.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * novelbase 知识图谱可视化控制器
 * <p>
 * 读取 novelbase.db（Rust MCP 的数据库），提供 D3.js 力导向图。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/novelbase")
public class NovelbaseGraphController {

    private JdbcTemplate novelbaseJdbc;

    @Value("${app.novelbase.db-path:}")
    private String dbPath;

    private static final String HTML_PAGE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>novelbase 知识图谱</title>
<script src="https://d3js.org/d3.v7.min.js"></script>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; background: #1a1a2e; color: #eee; overflow: hidden; }
#toolbar { position: fixed; top: 0; left: 0; right: 0; z-index: 10; background: rgba(26,26,46,0.9); backdrop-filter: blur(8px); padding: 12px 20px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid #333; }
#toolbar h1 { font-size: 16px; font-weight: 600; color: #e94560; }
#toolbar input { padding: 6px 12px; border-radius: 6px; border: 1px solid #444; background: #16213e; color: #eee; font-size: 14px; width: 200px; outline: none; }
#toolbar input:focus { border-color: #e94560; }
#toolbar select { padding: 6px 12px; border-radius: 6px; border: 1px solid #444; background: #16213e; color: #eee; font-size: 14px; outline: none; }
#legend { position: fixed; bottom: 20px; right: 20px; z-index: 10; background: rgba(22,33,62,0.9); padding: 10px 14px; border-radius: 8px; font-size: 12px; border: 1px solid #333; }
#legend .item { display: flex; align-items: center; gap: 6px; margin: 3px 0; }
#legend .dot { width: 10px; height: 10px; border-radius: 50%; }
#info { position: fixed; bottom: 20px; left: 20px; z-index: 10; background: rgba(22,33,62,0.9); padding: 10px 14px; border-radius: 8px; font-size: 13px; border: 1px solid #333; max-width: 300px; display: none; }
#info h3 { color: #e94560; margin-bottom: 4px; }
#info p { margin: 2px 0; color: #aaa; }
svg { width: 100vw; height: 100vh; }
</style>
</head>
<body>
<div id="toolbar">
    <h1>📖 novelbase</h1>
    <input id="search" type="text" placeholder="搜索节点..." oninput="onSearch(this.value)">
    <select id="project-select" onchange="loadGraph()"></select>
</div>
<div id="legend">
    <div class="item"><span class="dot" style="background:#e94560"></span> 角色</div>
    <div class="item"><span class="dot" style="background:#0f3460"></span> 地点</div>
    <div class="item"><span class="dot" style="background:#16c79a"></span> 文件</div>
    <div class="item"><span class="dot" style="background:#f5a623"></span> 场景</div>
    <div class="item"><span class="dot" style="background:#7b2fbe"></span> 物品</div>
</div>
<div id="info"></div>
<svg id="graph"></svg>
<script>
const COLORS = { character: "#e94560", location: "#0f3460", file: "#16c79a", scene: "#f5a623", item: "#7b2fbe", other: "#666" };
let svg = d3.select("#graph"), width = window.innerWidth, height = window.innerHeight, simulation, g, link, node, label;
function resize() { width = window.innerWidth; height = window.innerHeight; svg.attr("width", width).attr("height", height); }
window.onresize = resize; resize();
g = svg.append("g");
svg.call(d3.zoom().scaleExtent([0.1, 8]).on("zoom", (e) => { g.attr("transform", e.transform); }));
async function loadProjects() {
    const resp = await fetch("/api/novelbase/projects");
    const projects = await resp.json();
    const sel = document.getElementById("project-select");
    sel.innerHTML = projects.map(p => `<option value="${p}">${p}</option>`).join("");
    loadGraph();
}
async function loadGraph() {
    const project = document.getElementById("project-select").value;
    if (!project) return;
    const resp = await fetch("/api/novelbase/graph?project=" + encodeURIComponent(project));
    const data = await resp.json();
    if (!data.nodes || data.nodes.length === 0) {
        document.getElementById("info").style.display = "block";
        document.getElementById("info").innerHTML = "<p>⚠️ 暂无数据</p>";
        return;
    }
    g.selectAll("*").remove();
    document.getElementById("info").style.display = "none";
    simulation = d3.forceSimulation(data.nodes)
        .force("link", d3.forceLink(data.links).id(d => d.id).distance(100))
        .force("charge", d3.forceManyBody().strength(-300))
        .force("center", d3.forceCenter(width / 2, height / 2))
        .force("collision", d3.forceCollide(30));
    link = g.append("g").selectAll("line").data(data.links).join("line").attr("stroke", "#555").attr("stroke-width", 1.5).attr("stroke-opacity", 0.6);
    svg.append("defs").selectAll("marker").data(["end"]).join("marker").attr("id", "arrow").attr("viewBox", "0 -5 10 10").attr("refX", 22).attr("refY", 0).attr("markerWidth", 6).attr("markerHeight", 6).attr("orient", "auto").append("path").attr("d", "M0,-5L10,0L0,5").attr("fill", "#555");
    link.attr("marker-end", "url(#arrow)");
    node = g.append("g").selectAll("circle").data(data.nodes).join("circle").attr("r", 8).attr("fill", d => COLORS[d.group] || "#666").attr("stroke", "#fff").attr("stroke-width", 1.5).attr("cursor", "pointer").on("click", (e, d) => showInfo(d)).call(d3.drag().on("start", (e, d) => { if (!e.active) simulation.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; }).on("drag", (e, d) => { d.fx = e.x; d.fy = e.y; }).on("end", (e, d) => { if (!e.active) simulation.alphaTarget(0); d.fx = null; d.fy = null; }));
    label = g.append("g").selectAll("text").data(data.nodes).join("text").text(d => d.name).attr("font-size", "11px").attr("dx", 12).attr("dy", 4).attr("fill", "#aaa").attr("pointer-events", "none");
    simulation.on("tick", () => { link.attr("x1", d => d.source.x).attr("y1", d => d.source.y).attr("x2", d => d.target.x).attr("y2", d => d.target.y); node.attr("cx", d => d.x).attr("cy", d => d.y); label.attr("x", d => d.x).attr("y", d => d.y); });
}
function showInfo(d) { const info = document.getElementById("info"); info.style.display = "block"; info.innerHTML = `<h3>${d.name}</h3><p>类型: ${d.label}</p><p>ID: ${d.id}</p>`; }
function onSearch(text) { if (!node) return; node.attr("opacity", d => !text || d.name.includes(text) || d.label.toLowerCase().includes(text.toLowerCase()) ? 1 : 0.15); label.attr("opacity", d => !text || d.name.includes(text) || d.label.toLowerCase().includes(text.toLowerCase()) ? 1 : 0.15); }
loadProjects();
</script>
</body>
</html>""";

    @PostConstruct
    public void init() {
        String path = resolveDbPath();
        if (path == null || !Files.exists(Path.of(path))) {
            log.warn("novelbase.db 未找到，图谱页面不可用");
            return;
        }
        DataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + path, "", "");
        novelbaseJdbc = new JdbcTemplate(ds);
        log.info("novelbase 图谱数据源已连接: {}", path);
    }

    private String resolveDbPath() {
        if (dbPath != null && !dbPath.isBlank()) {
            return dbPath;
        }
        Path start = Path.of("").toAbsolutePath().normalize();
        Path candidate = start.resolve("../../novelbase-memory-mcp/novelbase.db").normalize();
        if (Files.exists(candidate)) return candidate.toString();
        candidate = Path.of("/Users/xiaozhang/project/novelbase-memory-mcp/novelbase.db");
        if (Files.exists(candidate)) return candidate.toString();
        return null;
    }

    /** 图谱页面 */
    @GetMapping("/graph-view")
    public String graphView() {
        return HTML_PAGE;
    }

    /** 获取所有项目列表 */
    @GetMapping("/projects")
    public List<String> listProjects() {
        if (novelbaseJdbc == null) return List.of();
        try {
            return novelbaseJdbc.query("SELECT DISTINCT project FROM nodes ORDER BY project",
                (rs, i) -> rs.getString("project"));
        } catch (Exception e) {
            log.warn("查询项目列表失败: {}", e.getMessage());
            return List.of("default");
        }
    }

    /** 获取图谱数据（节点 + 边） */
    @GetMapping("/graph")
    public Map<String, Object> getGraph(@RequestParam(defaultValue = "default") String project) {
        Map<String, Object> result = new HashMap<>();
        if (novelbaseJdbc == null) {
            result.put("nodes", List.of());
            result.put("links", List.of());
            return result;
        }
        try {
            List<Map<String, Object>> nodes = novelbaseJdbc.query(
                "SELECT id, name, label FROM nodes WHERE project = ?",
                (rs, i) -> {
                    Map<String, Object> n = new HashMap<>();
                    n.put("id", rs.getLong("id"));
                    n.put("name", rs.getString("name"));
                    n.put("label", rs.getString("label"));
                    n.put("group", switch (rs.getString("label")) {
                        case "Character" -> "character";
                        case "Location" -> "location";
                        case "File" -> "file";
                        case "Scene" -> "scene";
                        case "Item" -> "item";
                        default -> "other";
                    });
                    return n;
                }, project
            );
            List<Map<String, Object>> links = novelbaseJdbc.query(
                "SELECT source_id, target_id, type FROM edges WHERE project = ?",
                (rs, i) -> {
                    Map<String, Object> e = new HashMap<>();
                    e.put("source", rs.getLong("source_id"));
                    e.put("target", rs.getLong("target_id"));
                    e.put("edge_type", rs.getString("type"));
                    return e;
                }, project
            );
            result.put("nodes", nodes);
            result.put("links", links);
        } catch (Exception e) {
            log.warn("查询图谱失败: {}", e.getMessage());
            result.put("nodes", List.of());
            result.put("links", List.of());
        }
        return result;
    }
}
