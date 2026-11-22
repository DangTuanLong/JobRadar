"""Versioned prompt templates for explainable hybrid job matching evaluation."""

from __future__ import annotations

from typing import Any

MATCHING_PROMPT_VERSION = "v1.0.0"

_MAX_DESCRIPTION_CHARS = 500
_MAX_EXPERIENCES_IN_PROMPT = 6

MATCHING_SYSTEM_INSTRUCTION = """You are a senior IT technical recruiter and career advisor.
Your job is to evaluate how well a candidate matches each retrieved IT job posting and produce
transparent, evidence-based scoring in the exact requested JSON schema.

Scoring rubric (0-100 integer per pillar):
1. skills (weight 50%): Overlap with required_skills and nice_to_have_skills.
   Give HIGHER confidence and weight to skills verified by "github" (proven public code)
   compared to CV-only claims.
2. experience (weight 25%): Alignment between the candidate's years_of_experience / role history
   and the job's level (intern, fresher, junior, mid, senior).
3. projects (weight 25%): Relevance of the candidate's projects, GitHub highlights, and portfolio
   to the company's domain and tech stack.

Strict anti-hallucination rules:
- Copy job_posting_id and company_id VERBATIM from the provided candidate list. NEVER invent IDs.
- Evaluate ONLY the provided job postings; return one evaluation item per provided job_posting_id.
- NEVER invent URLs, salaries, or company facts not present in the input or web research snippets.
- List matched_skills and missing_skills concisely using clean technology names.
- Write reasoning in clear, natural Vietnamese (2-4 sentences): state specific matched strengths
  (explicitly noting GitHub-verified skills if applicable), relevant company context if available,
  and actionable skill gaps."""


def _format_candidate_profile(profile: dict[str, Any]) -> str:
    lines: list[str] = []
    if profile.get("full_name"):
        lines.append(f"Candidate: {profile['full_name']}")
    if profile.get("headline"):
        lines.append(f"Headline: {profile['headline']}")
    years = profile.get("years_of_experience", 0.0)
    lines.append(f"Years of Experience: {years}")

    skills = profile.get("skills") or []
    github_verified: list[str] = []
    other_skills: list[str] = []
    for item in skills:
        if not isinstance(item, dict) or not item.get("name"):
            continue
        verified_by = item.get("verified_by") or []
        label = f"{item['name']} ({item.get('level', 'intermediate')})"
        if "github" in verified_by:
            github_verified.append(f"{label} [verified: {', '.join(verified_by)}]")
        else:
            sources = ", ".join(verified_by) if verified_by else "cv"
            other_skills.append(f"{label} [{sources}]")

    if github_verified:
        lines.append(f"GitHub-Verified Skills (High Trust): {', '.join(github_verified)}")
    if other_skills:
        lines.append(f"Other Declared Skills: {', '.join(other_skills)}")

    experiences = (profile.get("experiences") or [])[:_MAX_EXPERIENCES_IN_PROMPT]
    if experiences:
        exp_lines: list[str] = []
        for exp in experiences:
            if not isinstance(exp, dict):
                continue
            title = exp.get("title") or "Untitled"
            org = exp.get("organization")
            techs = ", ".join(exp.get("technologies") or [])
            entry = f"{title} ({org})" if org else str(title)
            if techs:
                entry = f"{entry} - Tech: {techs}"
            exp_lines.append(entry)
        if exp_lines:
            lines.append("Projects & Experience: " + "; ".join(exp_lines))

    enrichment = profile.get("enrichment_summary") or {}
    if isinstance(enrichment, dict):
        highlights = enrichment.get("github_highlights") or []
        if highlights:
            lines.append("GitHub Highlights: " + "; ".join(str(h) for h in highlights[:5]))
        portfolio_summary = enrichment.get("portfolio_summary")
        if portfolio_summary:
            lines.append(f"Portfolio Summary: {str(portfolio_summary)[:300]}")

    if profile.get("profile_summary"):
        lines.append(f"Summary: {profile['profile_summary']}")

    return "\n".join(lines)


def _format_job_candidates(
    candidates: list[dict[str, Any]],
    web_research: dict[str, list[dict[str, str]]],
) -> str:
    blocks: list[str] = []
    for idx, job in enumerate(candidates, start=1):
        company_id = str(job.get("company_id", ""))
        job_posting_id = str(job.get("job_posting_id", ""))
        req_skills = ", ".join(job.get("required_skills") or [])
        nice_skills = ", ".join(job.get("nice_to_have_skills") or [])
        tech_stack = ", ".join(job.get("company_tech_stack") or [])
        desc = str(job.get("job_description") or "")[:_MAX_DESCRIPTION_CHARS]
        similarity = float(job.get("similarity") or 0.0)

        lines = [
            f"### Candidate #{idx}",
            f"- job_posting_id: {job_posting_id}",
            f"- company_id: {company_id}",
            f"- company_name: {job.get('company_name', 'Unknown')}",
            (
                f"- industry: {job.get('company_industry', '')} | "
                f"location: {job.get('company_location', '')}"
            ),
            f"- job_title: {job.get('job_title', '')} | level: {job.get('job_level', '')}",
            f"- vector_similarity: {similarity:.3f}",
            f"- required_skills: {req_skills}",
            f"- nice_to_have_skills: {nice_skills}",
            f"- company_tech_stack: {tech_stack}",
            f"- job_description: {desc}",
        ]

        snippets = web_research.get(company_id) or []
        if snippets:
            evidence_lines = [
                f"  * {s.get('title', '')}: {s.get('snippet', '')}"
                for s in snippets[:3]
            ]
            lines.append("- verified_web_research:\n" + "\n".join(evidence_lines))

        blocks.append("\n".join(lines))

    return "\n\n".join(blocks)


def build_matching_user_prompt(
    profile: dict[str, Any],
    candidates: list[dict[str, Any]],
    web_research: dict[str, list[dict[str, str]]],
) -> str:
    """Build the evaluation prompt combining candidate profile, JDs, and web context."""
    profile_block = _format_candidate_profile(profile)
    candidates_block = _format_job_candidates(candidates, web_research)
    return (
        f"Prompt version: {MATCHING_PROMPT_VERSION}\n\n"
        "## CANDIDATE PROFILE\n"
        f"{profile_block}\n\n"
        "## RETRIEVED JOB POSTINGS\n"
        f"{candidates_block}\n\n"
        "Evaluate each retrieved job posting above and return the LLMMatchBatchResponse JSON."
    )
