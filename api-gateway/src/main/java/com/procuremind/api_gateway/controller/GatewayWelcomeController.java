package com.procuremind.api_gateway.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class GatewayWelcomeController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String welcomeHtml() {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>ProcureMind • API Gateway</title>
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=Fira+Code:wght@400;600&display=swap" rel="stylesheet">
            <style>
                :root {
                    --bg-primary: #0a0f1d;
                    --bg-secondary: #0f172a;
                    --card-bg: rgba(30, 41, 59, 0.7);
                    --border-color: rgba(59, 130, 246, 0.2);
                    --accent-blue: #3b82f6;
                    --accent-cyan: #06b6d4;
                    --accent-green: #10b981;
                    --accent-purple: #8b5cf6;
                    --text-primary: #f8fafc;
                    --text-secondary: #94a3b8;
                    --text-muted: #64748b;
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
                    background: radial-gradient(circle at 50% 0%, #1e293b 0%, #0a0f1d 75%);
                    color: var(--text-primary);
                    min-height: 100vh;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    padding: 24px;
                }
                .container {
                    max-width: 900px;
                    width: 100%;
                    background: var(--card-bg);
                    backdrop-filter: blur(16px);
                    -webkit-backdrop-filter: blur(16px);
                    border: 1px solid var(--border-color);
                    border-radius: 20px;
                    padding: 40px;
                    box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.6), 0 0 40px -10px rgba(59, 130, 246, 0.15);
                }
                .header {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
                    padding-bottom: 24px;
                    margin-bottom: 32px;
                }
                .logo-section {
                    display: flex;
                    align-items: center;
                    gap: 16px;
                }
                .logo-icon {
                    font-size: 2.5rem;
                    background: linear-gradient(135deg, rgba(59, 130, 246, 0.2), rgba(139, 92, 246, 0.2));
                    padding: 12px;
                    border-radius: 14px;
                    border: 1px solid rgba(59, 130, 246, 0.4);
                }
                .title-area h1 {
                    font-size: 1.75rem;
                    font-weight: 800;
                    letter-spacing: -0.02em;
                    background: linear-gradient(90deg, #60a5fa, #a78bfa, #38bdf8);
                    -webkit-background-clip: text;
                    -webkit-text-fill-color: transparent;
                }
                .title-area p {
                    color: var(--text-secondary);
                    font-size: 0.9rem;
                    margin-top: 4px;
                }
                .status-badge {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    background: rgba(16, 185, 129, 0.12);
                    border: 1px solid rgba(16, 185, 129, 0.35);
                    color: var(--accent-green);
                    padding: 6px 14px;
                    border-radius: 9999px;
                    font-size: 0.82rem;
                    font-weight: 600;
                    letter-spacing: 0.02em;
                }
                .status-dot {
                    width: 8px;
                    height: 8px;
                    background: var(--accent-green);
                    border-radius: 50%;
                    box-shadow: 0 0 8px var(--accent-green);
                    animation: pulse 2s infinite;
                }
                @keyframes pulse {
                    0%, 100% { opacity: 1; transform: scale(1); }
                    50% { opacity: 0.4; transform: scale(0.85); }
                }
                .grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
                    gap: 20px;
                    margin-bottom: 32px;
                }
                .card {
                    background: rgba(15, 23, 42, 0.6);
                    border: 1px solid rgba(255, 255, 255, 0.06);
                    border-radius: 12px;
                    padding: 20px;
                    transition: transform 0.2s, border-color 0.2s, box-shadow 0.2s;
                    text-decoration: none;
                    color: inherit;
                    display: flex;
                    flex-direction: column;
                }
                .card:hover {
                    transform: translateY(-3px);
                    border-color: var(--accent-blue);
                    box-shadow: 0 10px 25px -5px rgba(59, 130, 246, 0.2);
                }
                .card-header {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    margin-bottom: 10px;
                }
                .card-title {
                    font-size: 1rem;
                    font-weight: 700;
                    color: var(--text-primary);
                }
                .card-desc {
                    font-size: 0.83rem;
                    color: var(--text-secondary);
                    line-height: 1.45;
                    margin-bottom: 14px;
                    flex-grow: 1;
                }
                .route-tag {
                    font-family: 'Fira Code', monospace;
                    font-size: 0.76rem;
                    background: rgba(59, 130, 246, 0.1);
                    color: #93c5fd;
                    border: 1px solid rgba(59, 130, 246, 0.25);
                    padding: 3px 8px;
                    border-radius: 6px;
                    align-self: flex-start;
                }
                .footer {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    border-top: 1px solid rgba(255, 255, 255, 0.08);
                    padding-top: 20px;
                    font-size: 0.82rem;
                    color: var(--text-muted);
                }
                .footer a {
                    color: var(--accent-blue);
                    text-decoration: none;
                }
                .footer a:hover { text-decoration: underline; }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <div class="logo-section">
                        <div class="logo-icon">🛡️</div>
                        <div class="title-area">
                            <h1>ProcureMind API Gateway</h1>
                            <p>Edge Reverse Proxy & Microservices Routing Layer</p>
                        </div>
                    </div>
                    <div class="status-badge">
                        <div class="status-dot"></div>
                        GATEWAY ACTIVE (Port 8080)
                    </div>
                </div>

                <div class="grid">
                    <a href="http://localhost:8501" target="_blank" class="card">
                        <div class="card-header">
                            <span class="card-title">📊 Executive UI Dashboard</span>
                            <span>🌐</span>
                        </div>
                        <p class="card-desc">Interactive Streamlit front-end for contract ingestion, risk breakdown, and AI chat assistant.</p>
                        <span class="route-tag">http://localhost:8501</span>
                    </a>

                    <a href="/api/contracts" class="card">
                        <div class="card-header">
                            <span class="card-title">📑 Contracts Service API</span>
                            <span>⚡</span>
                        </div>
                        <p class="card-desc">Contract PDF upload, MinIO object streaming, and status lifecycle management.</p>
                        <span class="route-tag">/api/contracts</span>
                    </a>

                    <a href="/api/analysis/dashboard-metrics" class="card">
                        <div class="card-header">
                            <span class="card-title">🤖 AI Service & Analytics</span>
                            <span>🧠</span>
                        </div>
                        <p class="card-desc">Clause extraction, risk scoring, financial exposure calculations, and vector retrieval.</p>
                        <span class="route-tag">/api/analysis/**</span>
                    </a>

                    <a href="/actuator/health" class="card">
                        <div class="card-header">
                            <span class="card-title">🩺 Gateway Health Probe</span>
                            <span>💚</span>
                        </div>
                        <p class="card-desc">Spring Boot Actuator liveness, readiness, and subsystem health status.</p>
                        <span class="route-tag">/actuator/health</span>
                    </a>

                    <a href="/health/contract" class="card">
                        <div class="card-header">
                            <span class="card-title">🏥 Contract Svc Health</span>
                            <span>🔍</span>
                        </div>
                        <p class="card-desc">Proxied health probe check for downstream Contract Service (port 8081).</p>
                        <span class="route-tag">/health/contract</span>
                    </a>

                    <a href="/health/ai" class="card">
                        <div class="card-header">
                            <span class="card-title">🏥 AI Service Health</span>
                            <span>🔍</span>
                        </div>
                        <p class="card-desc">Proxied health probe check for downstream AI Service (port 8082).</p>
                        <span class="route-tag">/health/ai</span>
                    </a>
                </div>

                <div class="footer">
                    <span>ProcureMind Platform • Spring Cloud Gateway MVC</span>
                    <span>Infrastructure: <a href="http://localhost:8085" target="_blank">Kafka UI (8085)</a> • <a href="http://localhost:9001" target="_blank">MinIO Console (9001)</a></span>
                </div>
            </div>
        </body>
        </html>
        """;
    }

    @GetMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> apiInfo() {
        return ResponseEntity.ok(Map.of(
            "service", "ProcureMind API Gateway",
            "status", "UP",
            "version", "1.0.0",
            "routes", Map.of(
                "contracts", "/api/contracts",
                "analysis", "/api/analysis",
                "gatewayHealth", "/actuator/health",
                "contractHealth", "/health/contract",
                "aiHealth", "/health/ai",
                "uiDashboard", "http://localhost:8501"
            )
        ));
    }
}
