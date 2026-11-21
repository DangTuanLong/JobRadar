-- ==============================================================================
-- 001_init_schema.sql
-- JobRadar Database Schema Initialization for Supabase (PostgreSQL + pgvector)
-- ==============================================================================

-- 1. EXTENSIONS
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. TRIGGER HELPER FUNCTION FOR updated_at
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

-- 3. TABLES DEFINITION

-- 3.1 candidate_profiles
CREATE TABLE IF NOT EXISTS public.candidate_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    full_name TEXT,
    headline TEXT,
    years_of_experience NUMERIC(4,1) NOT NULL DEFAULT 0.0,
    cv_file_url TEXT,
    skills JSONB NOT NULL DEFAULT '[]'::jsonb,
    experiences JSONB NOT NULL DEFAULT '[]'::jsonb,
    external_links JSONB NOT NULL DEFAULT '{}'::jsonb,
    enrichment_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    profile_summary TEXT,
    embedding vector(768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3.2 companies
CREATE TABLE IF NOT EXISTS public.companies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    website TEXT,
    industry TEXT,
    size_range TEXT,
    location TEXT,
    tech_stack TEXT[] NOT NULL DEFAULT '{}'::text[],
    description TEXT,
    culture_summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3.3 job_postings
CREATE TABLE IF NOT EXISTS public.job_postings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    level TEXT NOT NULL CHECK (level IN ('intern', 'fresher', 'junior', 'mid', 'senior')),
    required_skills TEXT[] NOT NULL DEFAULT '{}'::text[],
    nice_to_have_skills TEXT[] NOT NULL DEFAULT '{}'::text[],
    description TEXT,
    source_url TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    embedding vector(768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_job_postings_company_title_level UNIQUE (company_id, title, level)
);

-- 3.4 analysis_jobs
CREATE TABLE IF NOT EXISTS public.analysis_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    profile_id UUID REFERENCES public.candidate_profiles(id) ON DELETE SET NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'EXTRACTING', 'ENRICHING', 'MATCHING', 'COMPLETED', 'FAILED')),
    progress_percent INT NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    current_step_message TEXT,
    error_code TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3.5 match_results
CREATE TABLE IF NOT EXISTS public.match_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES public.analysis_jobs(id) ON DELETE CASCADE,
    profile_id UUID NOT NULL REFERENCES public.candidate_profiles(id) ON DELETE CASCADE,
    company_id UUID NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
    job_posting_id UUID REFERENCES public.job_postings(id) ON DELETE SET NULL,
    match_score INT NOT NULL CHECK (match_score BETWEEN 0 AND 100),
    score_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb,
    matched_skills TEXT[] NOT NULL DEFAULT '{}'::text[],
    missing_skills TEXT[] NOT NULL DEFAULT '{}'::text[],
    reasoning TEXT,
    web_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. TRIGGERS (Auto-update updated_at)
DROP TRIGGER IF EXISTS trg_candidate_profiles_updated_at ON public.candidate_profiles;
CREATE TRIGGER trg_candidate_profiles_updated_at
    BEFORE UPDATE ON public.candidate_profiles
    FOR EACH ROW
    EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS trg_companies_updated_at ON public.companies;
CREATE TRIGGER trg_companies_updated_at
    BEFORE UPDATE ON public.companies
    FOR EACH ROW
    EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS trg_analysis_jobs_updated_at ON public.analysis_jobs;
CREATE TRIGGER trg_analysis_jobs_updated_at
    BEFORE UPDATE ON public.analysis_jobs
    FOR EACH ROW
    EXECUTE FUNCTION public.set_updated_at();

-- 5. INDEXES

-- 5.1 HNSW Vector Indexes (Cosine Distance)
CREATE INDEX IF NOT EXISTS idx_job_postings_embedding_hnsw
    ON public.job_postings USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_candidate_profiles_embedding_hnsw
    ON public.candidate_profiles USING hnsw (embedding vector_cosine_ops);

-- 5.2 B-Tree Indexes for Foreign Keys and Frequent Queries
CREATE INDEX IF NOT EXISTS idx_candidate_profiles_user_id
    ON public.candidate_profiles(user_id);

CREATE INDEX IF NOT EXISTS idx_job_postings_company_active
    ON public.job_postings(company_id, is_active);

CREATE INDEX IF NOT EXISTS idx_job_postings_level_active
    ON public.job_postings(level, is_active);

CREATE INDEX IF NOT EXISTS idx_analysis_jobs_user_created
    ON public.analysis_jobs(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_match_results_job_score
    ON public.match_results(job_id, match_score DESC);

CREATE INDEX IF NOT EXISTS idx_match_results_profile
    ON public.match_results(profile_id);

-- 6. ROW LEVEL SECURITY (RLS)
ALTER TABLE public.candidate_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.companies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.job_postings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.analysis_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.match_results ENABLE ROW LEVEL SECURITY;

-- 6.1 candidate_profiles Policies
DROP POLICY IF EXISTS "Users can view own candidate profiles" ON public.candidate_profiles;
CREATE POLICY "Users can view own candidate profiles"
    ON public.candidate_profiles
    FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can insert own candidate profiles" ON public.candidate_profiles;
CREATE POLICY "Users can insert own candidate profiles"
    ON public.candidate_profiles
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update own candidate profiles" ON public.candidate_profiles;
CREATE POLICY "Users can update own candidate profiles"
    ON public.candidate_profiles
    FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- 6.2 companies Policies (Read-only for authenticated, write reserved for service_role)
DROP POLICY IF EXISTS "Authenticated users can view companies" ON public.companies;
CREATE POLICY "Authenticated users can view companies"
    ON public.companies
    FOR SELECT
    TO authenticated
    USING (true);

-- 6.3 job_postings Policies (Read-only for authenticated, write reserved for service_role)
DROP POLICY IF EXISTS "Authenticated users can view active job postings" ON public.job_postings;
CREATE POLICY "Authenticated users can view active job postings"
    ON public.job_postings
    FOR SELECT
    TO authenticated
    USING (true);

-- 6.4 analysis_jobs Policies (Backend service_role handles status updates)
DROP POLICY IF EXISTS "Users can view own analysis jobs" ON public.analysis_jobs;
CREATE POLICY "Users can view own analysis jobs"
    ON public.analysis_jobs
    FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can insert own analysis jobs" ON public.analysis_jobs;
CREATE POLICY "Users can insert own analysis jobs"
    ON public.analysis_jobs
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

-- 6.5 match_results Policies (User can only read results of profiles they own)
DROP POLICY IF EXISTS "Users can view own match results" ON public.match_results;
CREATE POLICY "Users can view own match results"
    ON public.match_results
    FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.candidate_profiles cp
            WHERE cp.id = match_results.profile_id
              AND cp.user_id = auth.uid()
        )
    );

-- 7. STORAGE BUCKET FOR CVs
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'cvs',
    'cvs',
    false,
    5242880, -- 5MB limit
    ARRAY['application/pdf', 'image/png', 'image/jpeg']
)
ON CONFLICT (id) DO NOTHING;

-- 7.1 Storage RLS Policies for 'cvs' bucket (<user_id>/* structure)
DROP POLICY IF EXISTS "Users can view own CV files" ON storage.objects;
CREATE POLICY "Users can view own CV files"
    ON storage.objects
    FOR SELECT
    TO authenticated
    USING (
        bucket_id = 'cvs' AND
        (storage.foldername(name))[1] = auth.uid()::text
    );

DROP POLICY IF EXISTS "Users can upload own CV files" ON storage.objects;
CREATE POLICY "Users can upload own CV files"
    ON storage.objects
    FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'cvs' AND
        (storage.foldername(name))[1] = auth.uid()::text
    );

DROP POLICY IF EXISTS "Users can update own CV files" ON storage.objects;
CREATE POLICY "Users can update own CV files"
    ON storage.objects
    FOR UPDATE
    TO authenticated
    USING (
        bucket_id = 'cvs' AND
        (storage.foldername(name))[1] = auth.uid()::text
    )
    WITH CHECK (
        bucket_id = 'cvs' AND
        (storage.foldername(name))[1] = auth.uid()::text
    );

DROP POLICY IF EXISTS "Users can delete own CV files" ON storage.objects;
CREATE POLICY "Users can delete own CV files"
    ON storage.objects
    FOR DELETE
    TO authenticated
    USING (
        bucket_id = 'cvs' AND
        (storage.foldername(name))[1] = auth.uid()::text
    );
