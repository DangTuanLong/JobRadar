"""Standardized 12-CV benchmark dataset and synthetic PDF generator with hidden GitHub links."""

from __future__ import annotations

from dataclasses import dataclass

import pymupdf


@dataclass(frozen=True, slots=True)
class BenchmarkCvSpec:
    """Single standardized candidate CV specification with ground-truth expectations."""

    cv_id: str
    candidate_label: str
    domain: str
    headline: str
    years_of_experience: float
    declared_skills: tuple[str, ...]
    projects: tuple[str, ...]
    summary: str
    github_url: str
    expected_role_keywords: tuple[str, ...]
    expected_level: str
    expected_min_github_skills: int


BENCHMARK_DATASET: tuple[BenchmarkCvSpec, ...] = (
    BenchmarkCvSpec(
        cv_id="cv_01_android_fresher",
        candidate_label="BENCHMARK CANDIDATE 01",
        domain="android",
        headline="Fresher Android Developer (Kotlin & Mobile UI)",
        years_of_experience=0.5,
        declared_skills=("Kotlin", "Android", "Coroutines", "MVVM", "Git"),
        projects=(
            "JobRadar Client: Mobile job matcher using MVVM, Coroutines, and REST APIs.",
            "NewsFeed Reader: Offline-capable mobile feed reader with clean state management.",
        ),
        summary="Android developer focused on modern Kotlin UI and clean mobile architecture.",
        github_url="https://github.com/chrisbanes",
        expected_role_keywords=("android", "kotlin", "mobile"),
        expected_level="fresher",
        expected_min_github_skills=1,
    ),
    BenchmarkCvSpec(
        cv_id="cv_02_backend_python_junior",
        candidate_label="BENCHMARK CANDIDATE 02",
        domain="backend_python",
        headline="Junior Backend Engineer (Python & REST APIs)",
        years_of_experience=1.5,
        declared_skills=("Python", "FastAPI", "SQL", "Git"),
        projects=(
            "Async API Gateway: High-throughput service built with FastAPI and SQL migrations.",
            "Task Queue Worker: Background job runner handling webhook retries cleanly.",
        ),
        summary="Backend engineer building typed Python web APIs and reliable data services.",
        github_url="https://github.com/tiangolo",
        expected_role_keywords=("backend", "python", "fastapi"),
        expected_level="junior",
        expected_min_github_skills=2,
    ),
    BenchmarkCvSpec(
        cv_id="cv_03_frontend_react_mid",
        candidate_label="BENCHMARK CANDIDATE 03",
        domain="frontend_react",
        headline="Middle Frontend Engineer (React & TypeScript)",
        years_of_experience=3.5,
        declared_skills=("React", "TypeScript", "JavaScript", "Tailwind CSS", "Git"),
        projects=(
            "Enterprise Design System: Accessible UI primitives and token-driven components.",
            "Analytics Portal: Server-rendered web portal focused on Core Web Vitals.",
        ),
        summary="Frontend specialist crafting scalable React and TypeScript web applications.",
        github_url="https://github.com/leerob",
        expected_role_keywords=("frontend", "react", "next.js", "typescript", "fullstack"),
        expected_level="mid",
        expected_min_github_skills=2,
    ),
    BenchmarkCvSpec(
        cv_id="cv_04_android_intern",
        candidate_label="BENCHMARK CANDIDATE 04",
        domain="android",
        headline="Android Developer Intern (Kotlin)",
        years_of_experience=0.0,
        declared_skills=("Kotlin", "Android", "Git"),
        projects=(
            "Weather Pocket App: Android app consuming REST weather endpoints.",
            "Campus Notes: Student note-taking mobile prototype with material components.",
        ),
        summary="Final-year IT student seeking an Android internship to grow Kotlin skills.",
        github_url="https://github.com/skydoves",
        expected_role_keywords=("android", "kotlin"),
        expected_level="intern",
        expected_min_github_skills=1,
    ),
    BenchmarkCvSpec(
        cv_id="cv_05_backend_python_intern",
        candidate_label="BENCHMARK CANDIDATE 05",
        domain="backend_python",
        headline="Backend Python Intern",
        years_of_experience=0.0,
        declared_skills=("Python", "SQL", "Flask", "Git"),
        projects=(
            "Library Catalog API: CRUD REST service with relational SQL schema.",
            "Expense Tracker Service: Lightweight JSON backend with automated tests.",
        ),
        summary="Computer science senior eager to build Python backend services.",
        github_url="https://github.com/miguelgrinberg",
        expected_role_keywords=("python", "backend"),
        expected_level="intern",
        expected_min_github_skills=1,
    ),
    BenchmarkCvSpec(
        cv_id="cv_06_java_spring_fresher",
        candidate_label="BENCHMARK CANDIDATE 06",
        domain="backend_java",
        headline="Fresher Java Backend Developer (Spring Boot)",
        years_of_experience=0.5,
        declared_skills=("Java", "Spring Boot", "SQL", "Git"),
        projects=(
            "Order Management API: Spring Boot REST service with relational persistence.",
            "Student Portal Backend: Role-based authentication and audit logging.",
        ),
        summary="Fresher Java developer focused on Spring Boot enterprise web services.",
        github_url="https://github.com/danvega",
        expected_role_keywords=("java", "spring", "backend"),
        expected_level="fresher",
        expected_min_github_skills=1,
    ),
    BenchmarkCvSpec(
        cv_id="cv_07_java_spring_mid",
        candidate_label="BENCHMARK CANDIDATE 07",
        domain="backend_java",
        headline="Middle Java Microservices Engineer",
        years_of_experience=4.0,
        declared_skills=("Java", "Spring Boot", "PostgreSQL", "Docker"),
        projects=(
            "Payment Ledger Service: Event-driven Java microservices for financial settlements.",
            "API Edge Router: High-availability Spring service containerized with Docker.",
        ),
        summary="Middle Java engineer experienced in distributed systems and Spring Cloud.",
        github_url="https://github.com/joshlong",
        expected_role_keywords=("java", "spring", "microservices"),
        expected_level="mid",
        expected_min_github_skills=1,
    ),
    BenchmarkCvSpec(
        cv_id="cv_08_frontend_react_fresher",
        candidate_label="BENCHMARK CANDIDATE 08",
        domain="frontend_react",
        headline="Fresher Frontend Developer (React)",
        years_of_experience=0.5,
        declared_skills=("React", "TypeScript", "JavaScript", "Git"),
        projects=(
            "Task Board UI: Responsive Kanban web app built with React hooks.",
            "Storefront Clone: E-commerce catalog with client-side filtering and cart.",
        ),
        summary="Fresher frontend engineer focused on clean React UI and TypeScript safety.",
        github_url="https://github.com/shadcn",
        expected_role_keywords=("frontend", "react", "typescript"),
        expected_level="fresher",
        expected_min_github_skills=1,
    ),
    BenchmarkCvSpec(
        cv_id="cv_09_fullstack_node_mid",
        candidate_label="BENCHMARK CANDIDATE 09",
        domain="fullstack",
        headline="Middle Fullstack Engineer (React & Node.js)",
        years_of_experience=3.5,
        declared_skills=("React", "TypeScript", "Node.js", "PostgreSQL"),
        projects=(
            "SaaS Billing Dashboard: Fullstack React and Node.js subscription platform.",
            "Team Collaboration App: Real-time workspace backed by PostgreSQL.",
        ),
        summary="Fullstack developer delivering end-to-end web features across UI and API.",
        github_url="https://github.com/kentcdodds",
        expected_role_keywords=("fullstack", "react", "node", "frontend"),
        expected_level="mid",
        expected_min_github_skills=2,
    ),
    BenchmarkCvSpec(
        cv_id="cv_10_flutter_fresher",
        candidate_label="BENCHMARK CANDIDATE 10",
        domain="flutter",
        headline="Fresher Flutter Mobile Developer",
        years_of_experience=0.5,
        declared_skills=("Dart", "Flutter", "Git"),
        projects=(
            "Habit Coach Mobile: Cross-platform Flutter app with local persistence.",
            "Food Delivery UI: Multi-screen mobile ordering prototype using Dart.",
        ),
        summary="Mobile engineer building smooth cross-platform iOS and Android apps in Flutter.",
        github_url="https://github.com/bizz84",
        expected_role_keywords=("flutter", "dart"),
        expected_level="fresher",
        expected_min_github_skills=1,
    ),
    BenchmarkCvSpec(
        cv_id="cv_11_data_ai_mid",
        candidate_label="BENCHMARK CANDIDATE 11",
        domain="data_ai",
        headline="Middle AI & Machine Learning Engineer",
        years_of_experience=3.5,
        declared_skills=("Python", "PyTorch", "SQL", "Git"),
        projects=(
            "Enterprise RAG Assistant: Retrieval-augmented QA pipeline over technical docs.",
            "Model Evaluation Suite: Automated benchmark harness for LLM and vision models.",
        ),
        summary="AI engineer specializing in PyTorch, retrieval pipelines, and LLM evaluation.",
        github_url="https://github.com/rasbt",
        expected_role_keywords=("ai", "data", "machine learning", "generative", "langgraph", "pytorch"),
        expected_level="mid",
        expected_min_github_skills=1,
    ),
    BenchmarkCvSpec(
        cv_id="cv_12_devops_junior",
        candidate_label="BENCHMARK CANDIDATE 12",
        domain="devops",
        headline="Junior DevOps & Cloud Engineer",
        years_of_experience=2.0,
        declared_skills=("Docker", "Kubernetes", "GitHub Actions", "Git"),
        projects=(
            "Zero-Downtime CI/CD: Automated container build and rollout via GitHub Actions.",
            "Cluster Observability Stack: Containerized monitoring for microservice workloads.",
        ),
        summary="DevOps engineer automating deployments, containers, and cloud reliability.",
        github_url="https://github.com/geerlingguy",
        expected_role_keywords=("devops", "cloud", "kubernetes", "docker", "sre"),
        expected_level="junior",
        expected_min_github_skills=1,
    ),
)


def generate_cv_pdf(spec: BenchmarkCvSpec) -> bytes:
    """Generate a valid 1-page PDF CV with a hidden clickable URI link to GitHub."""
    doc = pymupdf.open()
    try:
        page = doc.new_page()
        lines = [
            f"{spec.candidate_label} - CURRICULUM VITAE",
            f"Headline: {spec.headline}",
            f"Years of Experience: {spec.years_of_experience:.1f}",
            f"Core Skills: {', '.join(spec.declared_skills)}",
            *[f"Project: {proj}" for proj in spec.projects],
            f"Summary: {spec.summary}",
        ]
        y_pos = 72
        for line in lines:
            page.insert_text((56, y_pos), line, fontsize=10.5)
            y_pos += 22

        # Anchor text intentionally omits the raw URL so Stage 1 tests LINK_URI extraction.
        link_y = y_pos + 12
        page.insert_text((56, link_y), "GitHub Portfolio (Verified Public Repositories)", fontsize=10.5)
        page.insert_link(
            {
                "kind": pymupdf.LINK_URI,
                "from": pymupdf.Rect(56, link_y - 12, 340, link_y + 6),
                "uri": spec.github_url,
            }
        )
        return doc.tobytes()
    finally:
        doc.close()
