"""Versioned prompt templates for multimodal CV extraction."""

from __future__ import annotations

CV_EXTRACTION_PROMPT_VERSION = "v1.0.0"

CV_EXTRACTION_SYSTEM_INSTRUCTION = """You are an expert technical recruiter and CV parser.
Your task is to extract structured candidate information accurately from the provided CV document
(and optional supplementary LinkedIn PDF) into the requested JSON schema.

Strict rules:
1. Extract ONLY facts supported by the attached document(s) or the extracted hyperlink list.
   Never invent skills, degrees, work history, or URLs.
2. Support both English and Vietnamese CVs cleanly. Normalize technical skill names concisely
   (e.g., "Kotlin", "Jetpack Compose", "FastAPI", "PostgreSQL", "Docker").
3. Classify each skill into the closest category:
   language | framework | database | cloud_devops | ai_ml | tool | soft_skill | other
   and estimate level:
   beginner | intermediate | advanced | expert
   based on stated years, project depth, and responsibilities.
4. Estimate total years_of_experience as a non-negative float (0.0 for students/interns/freshers
   without full-time work history).
5. Classify extracted URLs accurately into external_links:
   - github: candidate's GitHub profile URL (or primary GitHub URL)
   - linkedin: candidate's LinkedIn profile URL
   - portfolio: personal website, portfolio, blog, or live project URL
   - other: additional relevant public technical URLs (exclude mailto/tel links)
6. Write a concise, information-dense profile_summary (2-4 sentences in English) summarizing the
   candidate's target role, core technologies, and project/work strengths for semantic matching.
7. If the file is unreadable, blank, or clearly NOT a CV/resume, return empty lists for skills
   and experiences and set profile_summary to null."""


def build_cv_extraction_user_prompt(
    extracted_links: list[str],
    *,
    has_linkedin_pdf: bool = False,
) -> str:
    """Build the user prompt instructing Gemini on attached documents and PDF hyperlinks."""
    links_section = (
        "\n".join(f"- {url}" for url in extracted_links)
        if extracted_links
        else "- None detected in PDF annotations."
    )
    linkedin_note = (
        "A supplementary LinkedIn PDF profile is also attached as the second document. "
        "Merge non-duplicate skills, experiences, and profile details from both documents."
        if has_linkedin_pdf
        else "Only the primary CV document is attached."
    )
    return (
        f"Prompt version: {CV_EXTRACTION_PROMPT_VERSION}\n"
        f"{linkedin_note}\n\n"
        "Hidden/embedded hyperlinks extracted from PDF annotations:\n"
        f"{links_section}\n\n"
        "Cross-reference these hyperlinks with the document text and populate the structured "
        "CandidateProfileExtracted JSON output."
    )
