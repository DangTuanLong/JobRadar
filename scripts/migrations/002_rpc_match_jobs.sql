-- ==============================================================================
-- 002_rpc_match_jobs.sql
-- RPC Function: match_job_postings
-- High-speed vector similarity retrieval with HNSW cosine distance
-- ==============================================================================

CREATE OR REPLACE FUNCTION public.match_job_postings(
    query_embedding vector(768),
    match_threshold float DEFAULT 0.45,
    match_count int DEFAULT 15,
    filter_level text DEFAULT NULL
)
RETURNS TABLE (
    job_posting_id uuid,
    company_id uuid,
    company_name text,
    company_website text,
    company_industry text,
    company_location text,
    company_size_range text,
    company_tech_stack text[],
    company_description text,
    job_title text,
    job_level text,
    required_skills text[],
    nice_to_have_skills text[],
    job_description text,
    source_url text,
    similarity float
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, extensions
AS $$
BEGIN
    RETURN QUERY
    SELECT
        jp.id AS job_posting_id,
        c.id AS company_id,
        c.name AS company_name,
        c.website AS company_website,
        c.industry AS company_industry,
        c.location AS company_location,
        c.size_range AS company_size_range,
        c.tech_stack AS company_tech_stack,
        c.description AS company_description,
        jp.title AS job_title,
        jp.level AS job_level,
        jp.required_skills AS required_skills,
        jp.nice_to_have_skills AS nice_to_have_skills,
        jp.description AS job_description,
        jp.source_url AS source_url,
        (1 - (jp.embedding <=> query_embedding))::float AS similarity
    FROM public.job_postings jp
    JOIN public.companies c ON c.id = jp.company_id
    WHERE jp.is_active = TRUE
      AND jp.embedding IS NOT NULL
      AND (filter_level IS NULL OR jp.level = filter_level)
      AND (1 - (jp.embedding <=> query_embedding)) >= match_threshold
    ORDER BY jp.embedding <=> query_embedding ASC
    LIMIT match_count;
END;
$$;

-- Permissions: Only authenticated users and service_role can execute this RPC
REVOKE ALL ON FUNCTION public.match_job_postings(vector(768), float, int, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.match_job_postings(vector(768), float, int, text) TO authenticated, service_role;
