#!/usr/bin/env python3
"""小型真实 Agent 评测。默认仅列用例；--execute 才外发，所有模型请求仍经过应用限流。"""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def assess(case, detail):
    run = detail['run']
    tools = [step for step in detail['steps'] if step['kind'] == 'TOOL' and step['state'] == 'SUCCEEDED']
    checks = {
        'completed': run['state'] == 'SUCCEEDED',
        'required_tools': set(case['requiredTools']).issubset({step['name'] for step in tools}),
        'within_budget': run['modelCalls'] <= 3 and run['toolCalls'] <= 3,
    }
    if case.get('expectedDocumentText'):
        checks['expected_chunk_recalled'] = any(case['expectedDocumentText'] in hit.get('content', '')
            for step in tools if step['name'] == 'search_knowledge'
            for hit in json.loads(step['output']).get('data', {}).get('hits', []))
    return {'case': case['id'], 'runId': run['id'], 'state': run['state'], 'checks': checks,
            'mechanicalPass': all(checks.values()), 'modelAttempts': run['modelCalls'],
            'toolRequests': run['toolCalls'], 'tools': [step['name'] for step in tools],
            'answer': run['result'], 'humanReview': None, 'rubric': case['review']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true', help='创建并执行真实模型运行，会消耗额度')
    parser.add_argument('--prepare', action='store_true', help='导入固定集合资料并建立索引，可能调用向量 API')
    parser.add_argument('--case', help='只运行一个 case id')
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--report', help='重新检查之前保存的报告，只 GET 原运行，不重新执行模型')
    args = parser.parse_args()
    url = urllib.parse.urlparse(args.base_url)
    if url.scheme != 'http' or url.hostname not in ('127.0.0.1', 'localhost') or url.username or url.query or url.fragment:
        parser.error('仅允许本机 HTTP 应用地址')
    if args.report and (args.execute or args.prepare):
        parser.error('--report 不能与 --execute/--prepare 同时使用')
    cases = json.loads((ROOT / 'docs/evaluation/cases.json').read_text())
    if args.case:
        cases = [case for case in cases if case['id'] == args.case]
        if not cases: parser.error('未知 case id')

    def request(path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request(args.base_url.rstrip('/') + path, data=data,
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=115) as response:
            return json.load(response)

    output = ROOT / 'data/evaluation' / (time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6] + '.json')
    report = {'startedAt': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'suite': 'v5-small-agent-suite',
              'warning': '机械检查不等于答案正确率；humanReview 留空待人工核对。', 'results': []}

    def save():
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    if args.prepare:
        doc = request('/api/knowledge/documents', {'title': 'Java 集合：先定位，再修改',
                      'content': (ROOT / 'docs/examples/java-collections.md').read_text()})
        indexed = request(f"/api/knowledge/documents/{doc['id']}/index", {})
        print(f"固定资料 #{indexed['id']}：{indexed['state']}", flush=True)
    previous = json.loads(Path(args.report).read_text()) if args.report else None
    if not args.execute and not previous:
        print(json.dumps(cases, ensure_ascii=False, indent=2))
        return
    for index, case in enumerate(cases):
        if previous:
            old = next((r for r in previous['results'] if r['case'] == case['id']), None)
            if not old: continue
            run_id = old['runId']
        else:
            if index:
                print('等待 65 秒再开始下一个用例，保持独立的请求窗口。', flush=True)
                # 分段等待便于 Ctrl+C；不会自动重试失败或限流的运行。
                for _ in range(13): time.sleep(5)
            run = request('/api/agent/runs', {'requestId': str(uuid.uuid4()), 'prompt': case['prompt'], 'sessionId': None})
            run_id = run['id']
            report['results'].append({'case': case['id'], 'runId': run_id, 'state': 'UNCONFIRMED'})
            save()  # 执行请求响应丢失时仍能用 --report 找回原运行。
            print(f"执行 {case['id']}，运行 #{run_id}", flush=True)
            try:
                request(f'/api/agent/runs/{run_id}/execute', {})
            except (urllib.error.URLError, TimeoutError):
                print('执行响应未确认；保留运行编号，仅读取本地状态，不重发。', flush=True)
        detail = request(f'/api/agent/runs/{run_id}')
        result = assess(case, detail)
        if previous: report['results'].append(result)
        else: report['results'][-1] = result
        save()
        print(json.dumps({key: result[key] for key in ('case', 'runId', 'state', 'checks')}, ensure_ascii=False), flush=True)
    print(f'报告：{output}', flush=True)


if __name__ == '__main__':
    main()
