# LivingDocs --- Git Workflow & Collaboration Rules

> **Mục tiêu:** Bảo đảm mọi công việc trên Jira có thể truy vết xuyên
> suốt đến branch, commit, Pull Request (PR), kết quả CI và người
> review; đồng thời bảo vệ `develop` và `main` khỏi các thay đổi chưa
> được kiểm tra.

## 1. Phạm vi và nguyên tắc bắt buộc

Tài liệu này áp dụng cho tất cả thành viên tham gia phát triển
LivingDocs.

1.  **Jira là nguồn quản lý công việc.** Mọi thay đổi phục vụ một
    task/bug/story phải gắn với Jira issue key tương ứng.
2.  **Không làm việc trực tiếp trên `develop` hoặc `main`.** Mỗi task
    phải được thực hiện trên branch riêng.
3.  **Không push trực tiếp lên `develop` hoặc `main`.** Thay đổi chỉ
    được đưa vào `develop` thông qua PR đã được review và CI đạt yêu
    cầu.
4.  **Không tự ý merge vào `develop` hoặc `main`.** Chỉ người được chỉ
    định có quyền merge mới được merge, sau khi đáp ứng đầy đủ các điều
    kiện trong tài liệu này.
5.  **Không đưa code chưa review vào `main`.** `main` là nhánh ổn định;
    việc phát hành từ `develop` lên `main` phải theo quyết định của
    Technical Lead/Project Lead.
6.  **Mỗi PR phải có Jira key, mô tả thay đổi, cách kiểm thử và bằng
    chứng CI.**
7.  **Không commit secret, token, password, private key, file `.env`
    hoặc dữ liệu cá nhân.**

Các quy tắc này là quy trình làm việc của nhóm. Cần cấu hình GitHub
branch protection/rulesets để cưỡng chế các quy tắc quan trọng; chỉ ghi
trong tài liệu là chưa đủ.

## 2. Quy ước Jira và truy vết

### 2.1. Jira issue key là bắt buộc

Mỗi branch, commit và PR phải chứa Jira issue key, ví dụ:

-   `LIV-114`
-   `LIV-205`

Dùng đúng key của task đang thực hiện, không tự đặt key và không dùng
key của Epic/Story cha nếu công việc đã có sub-task riêng.

### 2.2. Một branch nên gắn với một Jira issue

-   Mặc định: **1 Jira issue = 1 branch = 1 PR**.
-   Nếu một issue cần nhiều PR (ví dụ backend và frontend tách riêng),
    tất cả PR phải tham chiếu cùng Jira issue và phải ghi rõ phần việc
    của từng PR.
-   Không gộp nhiều Jira issue không liên quan vào một branch/PR.
-   Nếu phát hiện scope vượt quá issue hiện tại, trao đổi với người phụ
    trách Jira để tạo/cập nhật issue trước khi mở rộng công việc.

### 2.3. Cập nhật Jira

Trước khi bắt đầu: - Xác nhận issue đã được giao cho mình hoặc được
người phụ trách phân công. - Đọc Description, Acceptance Criteria (AC),
Definition of Done (DoD), dependency và comment mới nhất. - Chuyển trạng
thái Jira theo workflow của dự án (ví dụ: To Do → In Progress).

Trong quá trình làm: - Cập nhật comment khi có blocker, thay đổi phạm vi
hoặc quyết định kỹ thuật quan trọng. - Ghi lại PR URL vào Jira nếu tích
hợp chưa tự liên kết. - Không chuyển issue sang Done chỉ vì đã tạo PR.
Chỉ chuyển Done khi AC/DoD của issue đã được đáp ứng và quy trình
review/merge theo yêu cầu của issue đã hoàn tất.

## 3. Quy tắc đặt tên branch

### 3.1. Cấu trúc

``` text
<type>/<JIRA-KEY>-<short-description>
```

`short-description` dùng tiếng Anh, chữ thường, kebab-case, không dấu,
không khoảng trắng.

### 3.2. Các loại branch

  --------------------------------------------------------------------------------------------
Type                    Khi sử dụng             Ví dụ
  ----------------------- ----------------------- --------------------------------------------
`feature`               Phát triển chức năng    `feature/LIV-205-document-version-history`
mới

`fix`                   Sửa bug                 `fix/LIV-218-tenant-filter-query`

`hotfix`                Sửa lỗi khẩn cấp trên   `hotfix/LIV-230-auth-token-expiry`
bản phát hành; cần được
Lead chấp thuận

`refactor`              Refactor không chủ đích `refactor/LIV-241-repository-adapter`
thay đổi behavior

`test`                  Bổ sung/chỉnh sửa test  `test/LIV-252-tenant-isolation-tests`

`docs`                  Chỉ thay đổi tài liệu   `docs/LIV-260-api-setup-guide`

`chore`                 Build, dependency,      `chore/LIV-270-backend-ci-workflow`
CI/CD hoặc công việc    
bảo trì
  --------------------------------------------------------------------------------------------

Nếu Jira issue là loại Task nhưng nội dung là phát triển chức năng, dùng
`feature`; chọn type theo bản chất thay đổi, không theo tên loại issue
trên Jira.

### 3.3. Quy tắc branch

-   Tạo branch từ `develop` mới nhất, trừ khi Lead chỉ định base khác.
-   Không đặt tên chung chung như `my-branch`, `test`, `update`,
    `fix-bug`.
-   Không dùng tên thành viên làm prefix.
-   Không dùng chung một branch cho nhiều task độc lập.
-   Không commit trực tiếp lên branch của người khác nếu chưa thống
    nhất.
-   Không force-push branch đang được người khác cùng sử dụng. Nếu cần
    rewrite history, phải thông báo và được đồng ý trước.

## 4. Quy tắc commit

### 4.1. Format bắt buộc

``` text
<type>(<JIRA-KEY>): <short imperative description>
```

Ví dụ:

``` text
feat(LIV-205): add document version history
fix(LIV-218): enforce tenant filter in repository query
test(LIV-252): add cross-tenant isolation coverage
docs(LIV-260): document local API setup
chore(LIV-270): provision PostgreSQL for backend CI
refactor(LIV-241): extract repository adapter
```

### 4.2. Commit type

Type         Ý nghĩa
  ------------ ------------------------------------------------
`feat`       Thêm chức năng
`fix`        Sửa lỗi
`test`       Thêm hoặc sửa kiểm thử
`docs`       Thay đổi tài liệu
`refactor`   Tái cấu trúc, không đổi behavior dự kiến
`chore`      Build, dependency, tooling, CI/CD, maintenance
`perf`       Cải thiện hiệu năng
`style`      Format/lint, không đổi logic
`revert`     Hoàn tác commit trước

### 4.3. Nội dung commit

-   Mỗi commit nên thể hiện một thay đổi logic có ý nghĩa, dễ review và
    rollback.
-   Dùng động từ mô tả rõ việc đã làm; ưu tiên câu ngắn, tiếng Anh.
-   Không dùng message như `update`, `fix`, `final`, `test`, `changes`.
-   Không gom thay đổi không liên quan vào một commit.
-   Không commit file build output, IDE local settings không cần thiết,
    log, secret hoặc `.env`.
-   Trước khi commit, kiểm tra `git status`, diff và test liên quan.
-   Nếu task có nhiều phần độc lập, chia commit theo phần việc để dễ
    review.

## 5. Quy trình thực hiện một Jira task

### Bước 1 --- Nhận task

1.  Mở Jira issue và xác nhận scope, AC, DoD, dependency.
2.  Nếu AC chưa rõ hoặc có mâu thuẫn, hỏi PO/BA/Technical Lead trước khi
    tự suy diễn.
3.  Chuyển issue sang trạng thái phù hợp, thường là `In Progress`.

### Bước 2 --- Đồng bộ và tạo branch

``` bash
git switch develop
git pull origin develop
git switch -c feature/LIV-205-document-version-history
```

Thay branch name bằng tên đúng theo Jira issue và loại công việc.

### Bước 3 --- Phát triển và commit

``` bash
git status
git diff
git add <files>
git commit -m "feat(LIV-205): add document version history"
```

-   Chỉ stage file thuộc task.
-   Chạy test/lint/build liên quan trước khi push.
-   Không bỏ qua test chỉ để làm CI xanh.

### Bước 4 --- Push branch

Lần đầu:

``` bash
git push -u origin feature/LIV-205-document-version-history
```

Các lần tiếp theo:

``` bash
git push
```

Push branch của task, tuyệt đối không push trực tiếp lên `develop` hoặc
`main`.

### Bước 5 --- Tạo Pull Request

-   Tạo PR trên GitHub.
-   **Base branch mặc định: `develop`.**
-   **Compare branch:** branch của task.
-   Tiêu đề PR phải có Jira key.
-   PR phải được tạo ở trạng thái Draft nếu công việc chưa sẵn sàng
    review.
-   Chỉ chuyển sang Ready for review khi checklist ở Mục 6 đã đạt.

Ví dụ tiêu đề:

``` text
[LIV-205] feat: add document version history
```

### Bước 6 --- CI và review

1.  Chờ tất cả required CI checks hoàn tất.
2.  Nếu CI fail, tác giả phải phân tích log, sửa nguyên nhân và push
    commit cập nhật.
3.  Yêu cầu reviewer theo phân công của nhóm.
4.  Tác giả phản hồi từng review comment và cập nhật code khi cần.
5.  Nếu có thay đổi sau review, CI phải chạy lại và reviewer xác nhận
    phần thay đổi.
6.  Chỉ được merge khi đủ điều kiện tại Mục 7.

### Bước 7 --- Hoàn tất Jira

Sau khi PR được merge: - Kiểm tra thay đổi đã vào đúng base branch. -
Cập nhật Jira issue với PR URL, commit/merge reference và kết quả kiểm
thử nếu cần. - Chuyển trạng thái Jira theo workflow; chỉ chuyển Done khi
AC/DoD thực sự hoàn tất. - Xóa branch sau merge nếu không còn cần thiết.

## 6. Quy tắc tạo Pull Request

### 6.1. Tiêu đề PR

``` text
[<JIRA-KEY>] <type>: <short description>
```

Ví dụ:

``` text
[LIV-205] feat: add document version history
[LIV-218] fix: enforce tenant filter in repository query
[LIV-270] chore: provision PostgreSQL for backend CI
```

### 6.2. Nội dung PR tối thiểu

Mỗi PR phải có:

``` markdown
## Jira
- Issue: LIV-205
- Link: https://huutiense25.atlassian.net/browse/LIV-205

## Summary
- Mô tả ngắn gọn thay đổi chính.
- Nêu rõ phần nào được thêm/sửa/xóa.

## Scope
- In scope:
- Out of scope:

## Implementation notes
- Các quyết định kỹ thuật đáng chú ý.
- Migration/config/API/event thay đổi (nếu có).

## Testing
- [ ] Unit tests
- [ ] Integration tests
- [ ] Build/package
- [ ] Manual verification (nếu cần)
- Kết quả/lệnh kiểm thử:

## Risk & impact
- Ảnh hưởng đến service/module/database/API.
- Migration hoặc cấu hình cần lưu ý.
- Rủi ro và cách rollback (nếu có).

## Checklist
- [ ] PR gắn đúng Jira issue.
- [ ] Đã tự review diff.
- [ ] Không chứa secret hoặc file local.
- [ ] Đã cập nhật test/tài liệu liên quan.
- [ ] CI đã PASS.
- [ ] Đã yêu cầu reviewer.
```

### 6.3. Liên kết PR với Jira

-   Luôn ghi Jira key và URL trong PR title/body.
-   Có thể dùng `Refs LIV-205` trong PR description để tạo liên kết mà
    không tự động đóng issue.
-   Chỉ dùng `Closes LIV-205` hoặc cú pháp tương đương khi PR đó thực sự
    hoàn tất toàn bộ issue và nhóm muốn GitHub tự đóng issue tương ứng.
    Với issue có nhiều PR hoặc còn việc sau merge, **không dùng
    `Closes`**.
-   Nếu PR xử lý nhiều issue, phải giải thích rõ lý do và phạm vi từng
    issue; mặc định nên tách PR.

## 7. Điều kiện bắt buộc trước khi merge vào `develop`

Một PR chỉ đủ điều kiện merge khi **tất cả** điều kiện sau được đáp ứng:

-   [ ] Base branch là `develop`, trừ khi có chỉ định khác bằng văn bản.
-   [ ] PR có Jira key hợp lệ và mô tả đủ để truy vết.
-   [ ] Tác giả đã tự review toàn bộ diff.
-   [ ] Tất cả required CI checks đều **PASS**.
-   [ ] Có ít nhất **1 approval** từ reviewer được nhóm phân công.
-   [ ] Không còn review conversation/request changes chưa được giải
    quyết.
-   [ ] Các thay đổi quan trọng đã có test phù hợp.
-   [ ] Database migration, API contract, event contract, config và tài
    liệu liên quan đã được cập nhật nếu bị ảnh hưởng.
-   [ ] Không có secret, credential hoặc file local nhạy cảm trong PR.
-   [ ] Không có conflict với `develop`.
-   [ ] Người có quyền merge xác nhận PR đáp ứng AC/DoD của Jira issue.

**Không được tự approve PR của chính mình.** Tác giả PR không được tính
là reviewer độc lập.

Nếu CI bị lỗi do hạ tầng hoặc lỗi ngoài phạm vi PR, không được tự ý bỏ
qua required check. Báo Technical Lead/maintainer để xác minh và quyết
định cách xử lý có ghi nhận.

## 8. Quy tắc bảo vệ `develop` và `main`

### 8.1. `develop`

-   Là nhánh tích hợp công việc đã qua PR review.
-   Không commit trực tiếp.
-   Không push trực tiếp.
-   Không force-push.
-   Không tự ý merge PR của chính mình.
-   Mọi thay đổi phải đi qua PR, CI và approval theo Mục 7.

### 8.2. `main`

-   Là nhánh ổn định phục vụ release/demo/deployment theo quy trình dự
    án.
-   Thành viên không được tự ý push, commit, merge hoặc tạo thay đổi
    trực tiếp trên `main`.
-   Không dùng `main` làm base branch cho feature PR thông thường.
-   Chỉ Technical Lead/Project Lead hoặc người được ủy quyền mới quyết
    định thời điểm và phương thức đưa thay đổi từ `develop` lên `main`.
-   Release PR vào `main` cũng phải có review và CI đạt yêu cầu.

### 8.3. Cấu hình GitHub bắt buộc khuyến nghị

Repository administrator cần thiết lập Branch protection hoặc Rulesets
cho cả `develop` và `main`:

-   Require a pull request before merging.
-   Require at least 1 approval.
-   Dismiss stale approvals khi có commit mới (khuyến nghị).
-   Require conversation resolution.
-   Require status checks to pass before merging; chọn đúng các required
    checks của workflow hiện tại.
-   Require branches to be up to date before merging (nếu nhóm chấp nhận
    yêu cầu cập nhật branch trước merge).
-   Block force pushes.
-   Block deletions.
-   Restrict who can push/merge, đặc biệt với `main`.
-   Không cho phép bypass protection tùy tiện; quyền bypass chỉ dành cho
    maintainer được chỉ định và phải có lý do.

Tên required status check phải chọn theo đúng tên job/check đang hiển
thị trên GitHub Actions; không tự đoán tên. Sau khi thay đổi workflow
CI, administrator cần kiểm tra lại branch protection để bảo đảm các
check bắt buộc vẫn được nhận diện.

## 9. Đồng bộ branch khi `develop` có thay đổi

Nếu branch task tồn tại lâu và `develop` đã có commit mới:

1.  Hoàn tất hoặc commit phần đang làm để tránh mất dữ liệu.
2.  Lấy cập nhật mới nhất:

``` bash
git fetch origin
git switch develop
git pull origin develop
git switch feature/LIV-205-document-version-history
```

3.  Đồng bộ `develop` vào branch task theo cách nhóm thống nhất. Ví dụ
    dùng merge:

``` bash
git merge develop
```

4.  Giải quyết conflict, chạy lại test, sau đó push branch.
5.  Không tự ý force-push để thay lịch sử branch đã được chia sẻ.

Nếu nhóm chọn rebase thay vì merge, chỉ rebase branch cá nhân chưa được
người khác cùng làm; phải thống nhất với reviewer trước khi rewrite
history.

## 10. Xử lý các trường hợp đặc biệt

### CI fail

-   Không yêu cầu merge khi required CI đang fail.
-   Đọc log và xác định nguyên nhân.
-   Tác giả sửa lỗi, commit theo đúng Jira key và push.
-   Chỉ tiếp tục review/merge khi CI đạt yêu cầu.

### Có blocker hoặc scope thay đổi

-   Comment trên Jira và thông báo người phụ trách.
-   Không tự mở rộng scope đáng kể mà không cập nhật AC/task.
-   Nếu cần, tạo issue mới và PR riêng.

### Cần hotfix

-   Chỉ dùng `hotfix/*` khi có sự cố khẩn cấp và được Technical
    Lead/Project Lead chấp thuận.
-   Vẫn phải có Jira issue, PR, CI và review.
-   Không lấy lý do hotfix để bỏ qua bảo vệ `main`; nếu cần quy trình
    khẩn cấp ngoại lệ, Lead phải phê duyệt và ghi lại lý do, phạm vi,
    người thực hiện và kế hoạch đồng bộ thay đổi về `develop`.

### PR bị tách hoặc thay đổi base

-   Trao đổi với reviewer/Lead trước khi đổi base.
-   PR phụ thuộc PR khác phải ghi rõ dependency và base strategy trong
    mô tả.
-   Không đổi base sang `main` để né CI hoặc review của `develop`.

## 11. Ví dụ đầy đủ

Giả sử được giao Jira issue `LIV-205` để bổ sung lịch sử phiên bản tài
liệu.

**Branch**

``` bash
git switch develop
git pull origin develop
git switch -c feature/LIV-205-document-version-history
```

**Commit**

``` bash
git add backend/document-governance-service
git commit -m "feat(LIV-205): add document version history"
git push -u origin feature/LIV-205-document-version-history
```

**PR**

``` text
Title: [LIV-205] feat: add document version history
Base: develop
Compare: feature/LIV-205-document-version-history
```

PR body phải có Jira link, summary, scope, testing, risk/impact và
checklist. Sau khi CI PASS, ít nhất một reviewer độc lập approve và các
comment được giải quyết, maintainer được chỉ định mới merge PR.

**Traceability chain**

``` text
Jira LIV-205
    ↓
feature/LIV-205-document-version-history
    ↓
feat(LIV-205): add document version history
    ↓
PR [LIV-205] feat: add document version history
    ↓
GitHub Actions: required checks PASS
    ↓
Reviewer approval + conversation resolved
    ↓
Authorized maintainer merges into develop
    ↓
Jira updated with PR / completion evidence
```

## 12. Checklist nhanh cho thành viên

### Trước khi code

-   [ ] Có Jira issue và đã hiểu AC/DoD.
-   [ ] Branch được tạo từ `develop` mới nhất.
-   [ ] Tên branch đúng format và có Jira key.

### Trước khi push

-   [ ] Commit message đúng format và có Jira key.
-   [ ] Đã kiểm tra diff/status.
-   [ ] Test liên quan đã chạy.
-   [ ] Không có secret, `.env`, log hoặc file local không cần thiết.

### Trước khi yêu cầu review

-   [ ] PR base là `develop`.
-   [ ] PR title/body có Jira key và link.
-   [ ] Summary, scope, testing, risk/impact đầy đủ.
-   [ ] Đã tự review diff.
-   [ ] CI PASS.

### Trước khi merge

-   [ ] Có ít nhất 1 approval độc lập.
-   [ ] Không còn conversation chưa resolve.
-   [ ] Required CI checks PASS.
-   [ ] Không conflict.
-   [ ] Người có quyền merge đã xác nhận.
-   [ ] Không tự ý merge vào `develop` hoặc `main`.

------------------------------------------------------------------------

**Quy tắc cuối cùng:** Nếu không truy vết được một thay đổi về Jira
issue, branch, commit và PR tương ứng thì thay đổi đó chưa đáp ứng quy
trình làm việc của LivingDocs. Khi chưa rõ cách xử lý, dừng trước bước
merge và hỏi Technical Lead/Project Lead.