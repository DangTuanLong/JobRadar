# Supabase Migrations Guide

Thư mục này chứa các tệp SQL migration để khởi tạo và quản lý lược đồ cơ sở dữ liệu Supabase cho dự án **JobRadar**.

---

## 1. Thứ tự chạy trên Supabase SQL Editor

Đăng nhập vào Supabase Dashboard -> chọn Project `JobRadar` -> vào **SQL Editor** và chạy lần lượt theo thứ tự sau:

1. **Bước 1: Chạy `001_init_schema.sql`**
   - Khởi tạo extension `uuid-ossp` và `vector`.
   - Tạo 5 bảng cốt lõi: `candidate_profiles`, `companies`, `job_postings`, `analysis_jobs`, `match_results`.
   - Tạo trigger tự động cập nhật `updated_at`.
   - Tạo index vector HNSW (`vector_cosine_ops`) và các B-Tree indexes.
   - Bật và cấu hình Row Level Security (RLS) cho 5 bảng.
   - Khởi tạo Storage bucket riêng tư `cvs` (giới hạn 5MB, PDF/PNG/JPEG) kèm RLS policies theo thư mục `<user_id>/*`.

2. **Bước 2: Chạy `002_rpc_match_jobs.sql`**
   - Tạo hàm RPC `public.match_job_postings` với `SET search_path = public, extensions;` để tìm kiếm tương đồng vector nhanh chóng.

---

## 2. Câu lệnh SQL kiểm chứng (Verification Queries)

Sau khi chạy xong 2 tệp trên, mở tab mới trong SQL Editor và chạy các câu lệnh dưới đây để kiểm tra:

### 2.1. Kiểm tra Extension
```sql
SELECT name, default_version, installed_version 
FROM pg_available_extensions 
WHERE name IN ('vector', 'uuid-ossp');
```

### 2.2. Kiểm tra 5 Bảng và trạng thái RLS
```sql
SELECT tablename, rowsecurity 
FROM pg_tables 
WHERE schemaname = 'public' 
  AND tablename IN ('candidate_profiles', 'companies', 'job_postings', 'analysis_jobs', 'match_results');
```
*Kỳ vọng: Cả 5 bảng đều có `rowsecurity = true`.*

### 2.3. Kiểm tra Indexes HNSW
```sql
SELECT indexname, indexdef 
FROM pg_indexes 
WHERE schemaname = 'public' 
  AND indexname LIKE '%hnsw%';
```
*Kỳ vọng: Thấy `idx_job_postings_embedding_hnsw` và `idx_candidate_profiles_embedding_hnsw`.*

### 2.4. Kiểm tra Storage Bucket `cvs`
```sql
SELECT id, name, public, file_size_limit, allowed_mime_types 
FROM storage.buckets 
WHERE id = 'cvs';
```
*Kỳ vọng: Bucket `cvs` tồn tại, `public = false`, `file_size_limit = 5242880`.*

### 2.5. Kiểm tra hàm RPC `match_job_postings`
```sql
-- Kiểm tra sự tồn tại của hàm
SELECT routine_name, routine_type 
FROM information_schema.routines 
WHERE routine_schema = 'public' AND routine_name = 'match_job_postings';

-- Thử gọi hàm với vector 768 chiều giá trị 0 (không lỗi cú pháp)
SELECT * FROM public.match_job_postings(
    query_embedding := array_fill(0.0::float4, ARRAY[768])::vector,
    match_threshold := 0.0,
    match_count := 5
);
```
*Kỳ vọng: Hàm thực thi thành công trả về 0 dòng (vì chưa có dữ liệu JD).*
