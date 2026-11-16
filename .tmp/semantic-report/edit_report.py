from pathlib import Path
from copy import deepcopy
from zipfile import ZipFile
from lxml import etree as E
from docx import Document
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT

ROOT = Path('D:/3-SoulSpace')
TARGET = ROOT / 'report/Bao_cao_CSDL_SoulSpace_V7.docx'
TMP = ROOT / '.tmp/semantic-report'
BACKUP = TMP / 'original.docx'
if not BACKUP.exists():
    BACKUP.write_bytes(TARGET.read_bytes())
source = Document(BACKUP)
doc = Document(BACKUP)
body = doc.element.body
start = next(p._p for p in doc.paragraphs if p.text.startswith('3.7. '))
end = next(p._p for p in doc.paragraphs if p.text.startswith('3.8. '))
insert_at = body.index(start)
removed = list(body)[insert_at:body.index(end)]
for node in removed:
    body.remove(node)
nodes = []

def para(text='', kind='body', bold=False, italic=False):
    p = doc.add_paragraph()
    p.style = source.paragraphs[155].style
    fmt = p.paragraph_format
    fmt.space_before = Pt(0)
    fmt.space_after = Pt(6)
    fmt.line_spacing = 1.15
    fmt.widow_control = True
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    if kind in ('h2', 'h3'):
        template = source.paragraphs[153 if kind == 'h2' else 154]
        p._p.remove(p._p.pPr)
        p._p.insert(0, deepcopy(template._p.pPr))
        p.alignment = WD_ALIGN_PARAGRAPH.LEFT
        p.paragraph_format.keep_with_next = True
    elif kind == 'label':
        bold = True
        fmt.space_before = Pt(6)
        fmt.keep_with_next = True
        p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    elif kind == 'caption':
        italic = True
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        fmt.keep_with_next = True
        fmt.space_before = Pt(4)
    elif kind == 'note':
        italic = True
        p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    r = p.add_run(text)
    r.bold = bold
    r.italic = italic
    r.font.name = 'Times New Roman'
    r.font.size = Pt(11.5 if kind == 'caption' else 13)
    r.font.color.rgb = RGBColor(0, 0, 0)
    p._p.getparent().remove(p._p)
    nodes.append(p._p)
    return p

def table(headers, rows, widths):
    t = doc.add_table(rows=1, cols=len(headers))
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    total = sum(widths)
    pr = t._tbl.tblPr
    tw = pr.find(qn('w:tblW'))
    tw.set(qn('w:w'), str(round(total * 1440)))
    tw.set(qn('w:type'), 'dxa')
    borders = OxmlElement('w:tblBorders')
    for edge in ['top','left','bottom','right','insideH','insideV']:
        b=OxmlElement('w:'+edge)
        for k,v in [('val','single'),('sz','4'),('color','D9D9D9')]:
            b.set(qn('w:'+k),v)
        borders.append(b)
    pr.append(borders)
    margins=OxmlElement('w:tblCellMar')
    for edge, size in [('top',80),('bottom',80),('left',100),('right',100)]:
        m=OxmlElement('w:'+edge);m.set(qn('w:w'),str(size));m.set(qn('w:type'),'dxa');margins.append(m)
    pr.append(margins)
    for col,w in zip(t.columns,widths): col.width=Inches(w)
    for c,text in zip(t.rows[0].cells,headers):c.text=text
    for row in rows:
        for c,text in zip(t.add_row().cells,row):c.text=text
    for i,row in enumerate(t.rows):
        row._tr.get_or_add_trPr().append(OxmlElement('w:cantSplit'))
        if i==0:
            row._tr.trPr.append(OxmlElement('w:tblHeader'))
        for j,c in enumerate(row.cells):
            c.width=Inches(widths[j]);c.vertical_alignment=WD_CELL_VERTICAL_ALIGNMENT.CENTER
            if i==0:
                sh=OxmlElement('w:shd');sh.set(qn('w:fill'),'E7E6E6');c._tc.get_or_add_tcPr().append(sh)
            for p in c.paragraphs:
                p.alignment=WD_ALIGN_PARAGRAPH.LEFT
                p.paragraph_format.space_after=Pt(0)
                p.paragraph_format.space_before=Pt(0)
                p.paragraph_format.line_spacing=1.05
                p.paragraph_format.keep_with_next=False
                for r in p.runs:
                    r.font.name='Times New Roman';r.font.size=Pt(11.5);r.bold=(i==0)
                    r.font.color.rgb=RGBColor(0,0,0)
    t._tbl.getparent().remove(t._tbl);nodes.append(t._tbl)

def math_run(text):
    r=OxmlElement('m:r');t=OxmlElement('m:t');t.text=text;r.append(t);return r

def equation(number, numerator=None, denominator=None, text=None):
    p=para()
    p.alignment=WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.keep_together=True
    mp=OxmlElement('m:oMathPara');om=OxmlElement('m:oMath');mp.append(om)
    if numerator:
        om.append(math_run('sim(q, vⱼ) = '))
        frac=OxmlElement('m:f')
        for tag,txt in [('num',numerator),('den',denominator)]:
            e=OxmlElement('m:'+tag);e.append(math_run(txt));frac.append(e)
        om.append(frac)
    else:om.append(math_run(text))
    om.append(math_run('     ('+number+')'))
    p._p.append(mp)

para('3.7. Xây dựng chức năng tìm kiếm ngữ nghĩa (Semantic Search)', 'h2')
para('3.7.1. Bài toán và yêu cầu tìm kiếm bài viết theo ngữ nghĩa', 'h3')
para('Trong SoulSpace, người dùng cần tìm các bài viết chia sẻ trải nghiệm hoặc cách giải quyết một vấn đề bằng câu mô tả tự nhiên. Câu truy vấn và bài viết có thể diễn đạt cùng ý bằng những từ ngữ khác nhau, nên việc chỉ kiểm tra từ khóa xuất hiện trong nội dung có thể bỏ sót bài liên quan hoặc trả về bài không đúng ngữ cảnh.')
para('Ví dụ, với truy vấn “cách vượt qua áp lực học tập cuối kỳ”, một bài chia sẻ về việc căng thẳng vì lịch thi dày đặc, chia nhỏ nội dung ôn tập và dành thời gian nghỉ có thể phù hợp dù không chứa nguyên cụm từ người dùng nhập. Đây là ví dụ minh họa nhu cầu tìm kiếm, chưa phải kết quả thực nghiệm.')
para('Bài toán đặt ra là: từ một câu truy vấn tiếng Việt, tìm và xếp hạng các bài viết công khai theo mức độ gần nghĩa với truy vấn. Đầu vào là văn bản tìm kiếm; đầu ra là danh sách bài viết gồm mã bài, tác giả, nội dung và điểm tương đồng, sắp xếp từ cao xuống thấp. Phạm vi xử lý là nội dung chữ của bài viết; tìm người dùng và tìm theo nội dung ảnh nằm ngoài chức năng này.')
para('Chức năng cần đáp ứng các yêu cầu: tiếp nhận câu truy vấn có nội dung; biểu diễn truy vấn và bài viết trong cùng không gian vector; chỉ xét bài công khai có nội dung chữ và vector hợp lệ khớp nội dung hiện tại; giới hạn số kết quả trả về; đồng bộ vector khi bổ sung dữ liệu. Bài riêng tư, bài chỉ có ảnh và bài chưa có vector phù hợp không được đưa vào tập kết quả.')

para('3.7.2. Thiết kế phương pháp tìm kiếm và lưu trữ vector', 'h3')
para('a) Phương pháp biểu diễn văn bản và công nghệ sử dụng', 'label')
para('Hệ thống sử dụng phương pháp truy hồi theo vector nhúng (embedding). Mỗi bài viết và câu truy vấn được chuyển thành một vector số bằng cùng mô hình Jina [10]. Hệ thống so sánh hướng của các vector để xếp hạng mức độ gần nghĩa. Mô hình đã được huấn luyện sẵn và được gọi qua API, không thực hiện huấn luyện lại trong phạm vi chức năng. Bảng 3.11 trình bày hai thành phần sử dụng cho bước tạo vector và lưu trữ, truy vấn.')
para('Bảng 3.11. Vai trò của Jina và Neon trong tìm kiếm ngữ nghĩa', 'caption')
table(['Thành phần','Cấu hình và vai trò'],[
    ['Jina','Dùng mô hình jina-embeddings-v3, đầu ra 1.024 chiều. Tác vụ retrieval.passage tạo vector bài viết; retrieval.query tạo vector câu truy vấn.'],
    ['Neon','Lưu cơ sở dữ liệu bài viết và vector. Trên Neon, kiểu vector(1024) và toán tử <=> hỗ trợ lưu vector, tính khoảng cách cosine và lấy danh sách bài theo thứ hạng.'],
], [1.12,5.27])
para('Quy trình gồm hai luồng. Luồng chuẩn bị dữ liệu đọc nội dung bài công khai, tạo vector bằng Jina và lưu trên Neon. Luồng tìm kiếm tạo vector cho câu truy vấn, so sánh với các vector bài viết đủ điều kiện rồi trả về nhóm có điểm cao nhất. Mỗi bài được biểu diễn bằng một vector của phần nội dung chữ, không chia thành các đoạn trong cách cài đặt hiện tại.')
para('[CẦN HÌNH 3.10: Sơ đồ hai luồng tìm kiếm ngữ nghĩa. Luồng chuẩn bị: nội dung bài công khai → Jina (retrieval.passage) → vector 1.024 chiều → Neon. Luồng truy vấn: câu tìm kiếm → Jina (retrieval.query) → vector truy vấn → Neon lọc bài hợp lệ, tính cosine và lấy top-k → danh sách kết quả.]', 'note')

para('b) Tổ chức dữ liệu phục vụ tìm kiếm', 'label')
para('Dữ liệu vector được lưu riêng trong blog_embeddings và liên kết với blogs qua mã bài viết. Cách tổ chức này giữ nội dung gốc ở bảng nghiệp vụ, đồng thời cho phép cập nhật vector độc lập. Bảng 3.12 mô tả các trường phục vụ trực tiếp cho phép tìm kiếm; chúng bổ sung cho lược đồ nghiệp vụ V1–V5 đã trình bày ở Mục 3.2.')
para('Bảng 3.12. Các trường chính của bảng blog_embeddings', 'caption')
table(['Trường','Kiểu dữ liệu','Ý nghĩa'],[
    ['blog_id','UUID','Khóa chính, đồng thời tham chiếu blogs.id; mỗi bài có tối đa một vector. Xóa bài thì xóa vector tương ứng.'],
    ['embedding','vector(1024)','Vector biểu diễn nội dung bài viết, gồm 1.024 thành phần số.'],
    ['content_sha256','TEXT','Mã băm SHA-256 của nội dung dùng để tạo vector; đối chiếu với nội dung hiện tại để loại vector đã lỗi thời.'],
], [1.27,1.12,4.0])
para('Khi truy vấn, blog_embeddings được nối với v_public_blog_feed theo blog_id để chỉ xét bài công khai. Điều kiện content_sha256 phải bằng mã băm của nội dung đang lưu giúp ngăn việc xếp hạng bài đã đổi nội dung bằng vector cũ. Bảng blog_embedding_jobs giữ các yêu cầu tạo vector ở nền, gồm mã bài, phiên bản công việc, trạng thái và thông tin thử lại; bảng này phục vụ đồng bộ, không tham gia tính điểm tương đồng.')

para('c) Công thức so sánh vector', 'label')
para('Áp dụng độ tương đồng cosine ở Công thức (2.1) cho vector truy vấn q và vector bài viết vⱼ, ta có:')
equation('3.1', numerator='∑ᵢ₌₁ᵈ qᵢvⱼᵢ', denominator='√(∑ᵢ₌₁ᵈ qᵢ²) · √(∑ᵢ₌₁ᵈ vⱼᵢ²)')
para('Bảng 3.13. Các ký hiệu trong công thức cosine', 'caption')
table(['Ký hiệu','Ý nghĩa'],[
    ['q; vⱼ','Lần lượt là vector câu truy vấn và vector của bài viết thứ j.'],
    ['d = 1.024','Số chiều của cả hai vector.'],
    ['i; qᵢ; vⱼᵢ','i chạy từ 1 đến d; qᵢ và vⱼᵢ là các thành phần ở chiều i.'],
    ['sim(q, vⱼ)','Điểm tương đồng cosine; với hai vector khác vector không, điểm thuộc [−1, 1]. Điểm càng lớn thì hai vector càng cùng hướng.'],
], [1.45,4.94])
para('Mẫu số là tích độ dài của hai vector, nên công thức đo mức độ cùng hướng thay vì độ lớn của chúng. Điểm gần 1 thể hiện hai vector gần cùng hướng. Điểm này được dùng để xếp hạng mức độ gần nghĩa, không phải xác suất bài viết đáp ứng đúng nhu cầu người dùng. Vector đầu vào phải có đúng số chiều, các giá trị hữu hạn và độ dài khác 0.')
para('Trên Neon, toán tử <=> trả khoảng cách cosine. Điểm tương đồng dùng trong danh sách kết quả được tính như sau [6], [11]:')
equation('3.2', text='dist(q, vⱼ) = 1 − sim(q, vⱼ)')
para('Trong truy vấn, similarity = 1 − (embedding <=> query_vector). Do đó, sắp xếp similarity giảm dần tương đương với sắp xếp khoảng cách cosine tăng dần. Khi hai bài có cùng điểm, hệ thống dùng mã bài tăng dần làm tiêu chí phụ để ổn định thứ tự.')

para('d) Tập ứng viên và quy tắc lựa chọn kết quả', 'label')
para('Gọi N là số bài công khai có nội dung chữ, đã có vector hợp lệ và có mã băm khớp với nội dung hiện tại tại thời điểm truy vấn. Hệ thống tính điểm cho toàn bộ N bài đủ điều kiện, sắp xếp theo cosine rồi chọn k bài đứng đầu (top-k). Số bài thực tế trả về là min(k, N); khi N = 0, danh sách kết quả rỗng. Phép tìm kiếm này xét toàn bộ tập ứng viên, không lấy ngẫu nhiên hoặc cắt trước một số bài rồi mới so sánh.')
para('Bảng 3.14. Giới hạn số kết quả theo cách gọi chức năng', 'caption')
table(['Trường hợp','Giá trị k','Cách sử dụng'],[
    ['Gửi truy vấn trên trang tìm kiếm','50','Trả tối đa 50 bài có điểm cao nhất; API cũng mặc định k = 50 và chấp nhận 1 ≤ k ≤ 50.'],
    ['Gợi ý bài viết khi nhập câu tìm kiếm','8','Giao diện yêu cầu tối đa 8 bài để hiển thị danh sách gợi ý.'],
    ['Chạy thử bằng lệnh tìm kiếm','10','Lệnh minh họa truy hồi tối đa 10 bài, độc lập với giới hạn mặc định của API.'],
], [2.0,0.65,3.74])
para('Ví dụ, nếu tập ứng viên có N = 350 bài thì truy vấn k = 50 xét cả 350 bài và chỉ trả 50 bài đứng đầu. Nếu chỉ có 20 bài đủ điều kiện thì trả cả 20 bài. Đây là ví dụ về quy tắc chọn kết quả, không phải số liệu thực nghiệm. Cách cài đặt hiện tại chưa áp dụng ngưỡng điểm tối thiểu, vì vậy các bài ở cuối danh sách vẫn có thể ít liên quan. Việc xác định ngưỡng phù hợp cần dựa vào đánh giá thực nghiệm sau này.')

para('3.7.3. Cài đặt chức năng tìm kiếm ngữ nghĩa', 'h3')
para('Việc cài đặt được tổ chức theo các bước dưới đây, từ chuẩn bị dữ liệu đến tích hợp chức năng tìm kiếm và đồng bộ vector.')
para('Bước 1. Chuẩn bị kết nối và cấu trúc lưu trữ', 'label')
para('Cấu hình khóa truy cập Jina và thông tin kết nối Neon ở phía máy chủ. Trên Neon, bật khả năng lưu và truy vấn vector, tạo blog_embeddings với khóa ngoại tới blogs, đồng thời chuẩn bị blog_embedding_jobs cho xử lý nền. Cấp quyền đọc, ghi các bảng cần thiết cho tài khoản chạy ứng dụng và kiểm tra việc truy cập khung nhìn bài công khai. Khóa truy cập Jina được giữ ở máy chủ, không đưa vào mã giao diện.')
para('[CẦN HÌNH 3.11: Ảnh cấu trúc trên Neon thể hiện blog_embeddings với các cột blog_id, embedding (vector 1.024 chiều), content_sha256 và khóa ngoại tới blogs; kèm bảng blog_embedding_jobs nếu chụp chung được. Không hiển thị khóa API hoặc thông tin đăng nhập.]', 'note')
para('Bước 2. Tạo và nạp vector cho dữ liệu ban đầu', 'label')
para('Đọc các bài công khai có nội dung chữ, tính mã băm nội dung rồi đối chiếu với vector đã lưu. Chỉ những bài chưa có vector hoặc có nội dung thay đổi mới cần tạo lại. Gửi nội dung theo lô tới Jina với model = jina-embeddings-v3, task = retrieval.passage và dimensions = 1024. Kiểm tra dữ liệu phản hồi và lưu vector cùng mã băm theo blog_id; cập nhật bản ghi nếu bài đã có vector. Trước khi ghi, kiểm tra bài vẫn công khai và nội dung chưa thay đổi so với dữ liệu đã gửi.')
para('Bước 3. Tiếp nhận truy vấn và tạo vector tìm kiếm', 'label')
para('Cung cấp API nhận câu tìm kiếm q và giới hạn limit. Loại khoảng trắng ở hai đầu, từ chối câu rỗng hoặc dài hơn 2.000 ký tự, đồng thời kiểm tra limit nằm trong khoảng từ 1 đến 50. Gọi Jina bằng cùng mô hình và số chiều như bài viết nhưng dùng task = retrieval.query. Chỉ tiếp tục khi phản hồi có đúng một vector 1.024 chiều, các thành phần hữu hạn và vector có độ dài khác 0; nếu dịch vụ không khả dụng thì trả thông báo để người dùng thử lại.')
para('Bước 4. Truy vấn và xếp hạng trên Neon', 'label')
para('Dùng vector truy vấn làm tham số, nối blog_embeddings với v_public_blog_feed và lọc các mã băm khớp nội dung hiện tại. Tính similarity theo Công thức (3.2), sắp xếp điểm giảm dần rồi mã bài tăng dần, cuối cùng áp dụng LIMIT theo số kết quả được yêu cầu. Trả danh sách gồm mã bài, nội dung, tên tác giả và điểm tương đồng. Nếu không có bài đủ điều kiện thì trả danh sách rỗng.')
para('Bước 5. Tích hợp vào giao diện tìm kiếm', 'label')
para('Khi người dùng gửi câu tìm kiếm, giao diện gọi API với giới hạn 50 và hiển thị danh sách bài theo thứ tự nhận được. Trong lúc nhập, chức năng gợi ý bài viết gọi cùng API với giới hạn 8. Giao diện thể hiện trạng thái đang tải, không có kết quả hoặc lỗi dịch vụ; việc tìm tài khoản được xử lý riêng, không dùng chung điểm cosine của bài viết.')
para('Bước 6. Đồng bộ vector cho bài viết mới', 'label')
para('Khi lưu bài mới, hệ thống đồng thời ghi yêu cầu vào hàng đợi trong giao dịch tạo bài. Sau khi giao dịch hoàn tất, tiến trình nền nhận công việc và gọi Jina bên ngoài giao dịch ghi dữ liệu để tránh giữ khóa trong lúc chờ dịch vụ. Trước khi lưu vector, tiến trình kiểm tra lại quyền công khai, nội dung và phiên bản công việc. Nếu nội dung đã thay đổi thì yêu cầu xử lý lại; nếu bài riêng tư hoặc không có chữ thì loại vector khỏi tập tìm kiếm. Công việc lỗi được thử lại có giới hạn, tối đa năm lượt xử lý.')
para('Luồng tự động hiện được gắn vào thao tác tạo bài mới. Khi triển khai thêm thao tác sửa nội dung hoặc đổi quyền bài viết, cần nối các thao tác đó vào cùng cơ chế đồng bộ. Trong thời gian chưa tạo lại vector, điều kiện mã băm của truy vấn loại bài có vector cũ; khi bài bị xóa, khóa ngoại xóa dữ liệu phụ thuộc. Các bước này mô tả cách cài đặt, chưa thay thế cho việc đánh giá chất lượng tìm kiếm bằng thực nghiệm.')

para('3.7.4. Kết quả và đánh giá', 'h3')
para()

for offset,node in enumerate(nodes):body.insert(insert_at+offset,node)

# Keep dependent references consistent without rewriting unrelated sections.
for p in doc.paragraphs:
    if 'Ảnh Semantic Search được bổ sung riêng tại Mục 3.7.3.' in p.text:
        for t in p._p.iter(qn('w:t')):
            if '3.7.3' in (t.text or ''):t.text=t.text.replace('3.7.3','3.7.4')
    if p.text.startswith('Nhóm sẽ đối chiếu một thao tác tương tác trên website'):
        for t in p._p.iter(qn('w:t')):
            if 'Với Semantic Search' in (t.text or ''):
                t.text=t.text.replace('nhóm cần bổ sung cách lưu vector, luồng truy vấn và kết quả so sánh trên cùng bộ câu tìm kiếm.', 'nhóm cần thiết kế thực nghiệm và bổ sung kết quả so sánh trên cùng bộ câu tìm kiếm.')

# Add sources for the two named components using the report's existing reference style.
last = next(p._p for p in doc.paragraphs if p.text.startswith('[9]'))
for txt in ['[10] Jina AI, “Embeddings API”. [Online]. Available https://jina.ai/embeddings/', '[11] Neon, “The pgvector extension”, Neon Docs. [Online]. Available https://neon.com/docs/extensions/pgvector']:
    new=deepcopy(last)
    for ch in list(new):
        if ch.tag!=qn('w:pPr'):new.remove(ch)
    r=OxmlElement('w:r');r.append(deepcopy(last.find(qn('w:r')).find(qn('w:rPr'))));t=OxmlElement('w:t');t.text=txt;r.append(t);new.append(r)
    last.addnext(new);last=new

# Mark existing contents/index fields for refresh without changing their cached view.
with ZipFile(BACKUP) as zin:
    docxml=E.tostring(doc.element, encoding='UTF-8', xml_declaration=True, standalone=True)
    with ZipFile(TMP/'edited.docx','w') as zout:
        for info in zin.infolist():
            data=zin.read(info.filename)
            if info.filename=='word/document.xml':data=docxml
            elif info.filename=='word/settings.xml':
                settings=E.fromstring(data)
                u=settings.find(qn('w:updateFields'))
                if u is None:u=OxmlElement('w:updateFields');settings.append(u)
                u.set(qn('w:val'),'true')
                data=E.tostring(settings, encoding='UTF-8', xml_declaration=True, standalone=True)
            zout.writestr(info,data)
TARGET.write_bytes((TMP/'edited.docx').read_bytes())

check=Document(TARGET)
heads=[p.text for p in check.paragraphs if p.text.startswith('3.7')]
assert len(heads)==5,heads
s=next(i for i,p in enumerate(check.paragraphs) if p.text=='3.7.4. Kết quả và đánh giá')
assert check.paragraphs[s+1].text==''
assert check.paragraphs[s+2].text.startswith('3.8.')
assert len(check.tables)==len(source.tables)+3
with ZipFile(BACKUP) as a,ZipFile(TARGET) as b:
    changed=[n for n in a.namelist() if a.read(n)!=b.read(n)]
assert set(changed)=={'word/document.xml','word/settings.xml'},changed
print('Updated:',TARGET)
print('Headings:',heads)
print('Tables added: 4; old results table removed: 1')
print('Package parts changed:',changed)
