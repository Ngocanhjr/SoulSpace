#!/usr/bin/env python3
"""Rebuild the provisional vote, retaining the distinction between votes and adjudication."""
import csv
import hashlib
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
paths = {
    'codex': ROOT / 'codex/comments_merged.clean.labeled.csv',
    'claude': ROOT / 'claude/comments_merged_labeled.csv',
    'chatweb': ROOT / 'chatweb/comments_merged.vihsd_annotated_draft.csv',
}
input_path = ROOT / 'codex/comments_merged.clean.csv'
def read(path):
    with path.open(encoding='utf-8-sig', newline='') as f:
        reader = csv.DictReader(f)
        return reader.fieldnames, list(reader)
fields, original = read(input_path)
sets = {name: read(path)[1] for name, path in paths.items()}
# Decisions made by the current reviewer on the 41 three-way disagreements only.
# The explanation is deliberately short; empty context is not filled with speculation.
decisions = {
40:(2,'Chê trí óc nhắm người cụ thể.'), 98:(0,'Ẩn dụ phê phán showbiz, không công kích người cụ thể.'),
102:(0,'Chê người này tệ, không đủ căn cứ là hạ nhục.'), 109:(0,'Chê ngoại hình hoặc cảm nhận, không có lời hạ nhục rõ.'),
110:(0,'Phủ nhận hành vi và nêu xu hướng tính dục, không tự động là công kích.'),
183:(2,'Câu chửi viết tắt nhắm người được trả lời.'), 197:(0,'Hỏi về seeding, chưa đủ căn cứ công kích.'),
262:(2,'Biệt danh miệt thị ngoại hình, ngụ ý đối tượng.'), 292:(1,'Câu tục cảm thán, mục tiêu công kích chưa rõ.'),
293:(2,'Dùng biểu tượng chó để hạ thấp người được nói đến.'), 295:(2,'Gọi người được nói đến là sáu tính.'),
373:(0,'Yêu cầu ngừng và hỏi về hành vi hùa, chưa đủ căn cứ hạ nhục.'),
560:(1,'Từ tục viết tắt trong lời cảm thán; không rõ là nhắm xúc phạm.'),
618:(0,'Một từ dê thiếu ngữ cảnh để xác định đối tượng và ý công kích.'),
620:(2,'Công kích người cụ thể bằng lời miệt thị và tục.'), 634:(2,'Miệt thị người đối thoại là kềnh kềnh.'),
692:(2,'Gọi người được so sánh là già dê.'), 721:(2,'Gọi người được nói đến là tiểu nhân.'),
725:(2,'Biệt danh giang đẫm dùng miệt thị người được nhắc đến.'),
741:(0,'Nhận xét và cáo buộc về hành vi, không đủ căn cứ là lời hạ nhục.'),
757:(2,'Miệt thị nhóm phụ nữ bằng cách nói bu vô húp.'), 790:(2,'Biệt danh miệt thị ngoại hình.'),
801:(2,'Biệt danh miệt thị người cụ thể.'), 844:(2,'Gọi người được nói đến là dê trong ngữ cảnh công kích.'),
848:(2,'Kết câu bằng lời miệt thị ngoại hình và nhân thân.'), 891:(2,'Gọi người cụ thể là dê với ý miệt thị.'),
892:(0,'Phê phán hành vi giả tạo và lừa dối; không đủ căn cứ hạ nhục.'),
896:(2,'Gọi người cụ thể là dê để miệt thị.'), 920:(2,'Dùng điếm, thâm để miệt thị người được nói đến.'),
1423:(0,'So sánh dáng chạy gây cười, chưa đủ căn cứ là hạ nhục.'),
1664:(2,'Miệt thị khuôn mặt và miệng người cụ thể.'), 2210:(2,'Gọi người cụ thể là quái vật.'),
2936:(2,'Đe dọa bạo lực nhắm người cụ thể.'), 2948:(2,'Ví một người ngụ ý là cục cớt.'),
2958:(0,'Nhận xét đệ tử làm mất mặt sư phụ.'), 2960:(2,'Mỉa mai người cụ thể thiếu não.'),
2962:(0,'Thành ngữ và chê hành vi rảnh, chưa đủ căn cứ hạ nhục.'),
2989:(0,'Bày tỏ ghét và chê hành vi khoe mẽ; ranh giới cần rà soát.'),
3004:(0,'Phê phán hành vi vỗ tay và tâm lý bầy đàn.'), 3017:(0,'Nhận xét về quan hệ, không có lời hạ nhục rõ.'),
3021:(0,'Nêu cáo buộc quan hệ tình dục, không tự động là công kích.'),
}
assert all(len(rows) == len(original) for rows in sets.values())
for name, rows in sets.items():
    for i, (source, row) in enumerate(zip(original, rows), 1):
        assert all(source[k] == row[k] for k in fields if k != 'label_id'), (name, i)
        assert row['label_id'] in ('0','1','2'), (name, i)
results, audit = [], []
for i, source in enumerate(original, 1):
    votes = {name: rows[i-1]['label_id'] for name, rows in sets.items()}
    counts = Counter(votes.values())
    is_tie = len(counts) == 3
    if is_tie:
        label, reason = decisions[i]
        label = str(label)
        method = 'reviewer_tiebreak'
        reviewer_vote = label
    else:
        label = counts.most_common(1)[0][0]
        method = 'unanimous' if len(counts) == 1 else 'majority'
        reviewer_vote, reason = '', ''
    row = source.copy()
    row['label_id'] = label
    results.append(row)
    audit.append({'data_row':i, 'source_id':source['source_id'], 'free_text':source['free_text'], 'context':source['context'],
                  **{f'vote_{k}':v for k,v in votes.items()}, 'vote_current_reviewer':reviewer_vote,
                  'final_label_id':label, 'decision_method':method, 'review_reason':reason,
                  'needs_review':'YES' if len(counts)>1 else 'NO'})
assert set(decisions) == {i for i, a in enumerate(audit,1) if a['decision_method']=='reviewer_tiebreak'}
def write(path, fieldnames, rows):
    with path.open('w',encoding='utf-8',newline='') as f:
        w=csv.DictWriter(f, fieldnames=fieldnames); w.writeheader(); w.writerows(rows)
out=Path(__file__).resolve().parent
write(out/'comments_merged.final.provisional.csv', fields, results)
write(out/'comments_merged.vote_audit.csv', list(audit[0]), audit)
write(out/'comments_merged.disagreements.csv', list(audit[0]), [a for a in audit if a['needs_review']=='YES'])
print('input sha256',hashlib.sha256(input_path.read_bytes()).hexdigest())
print('rows',len(results),'labels',Counter(r['label_id'] for r in results),'methods',Counter(a['decision_method'] for a in audit))
