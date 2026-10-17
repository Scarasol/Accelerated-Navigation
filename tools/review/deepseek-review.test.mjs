import test from "node:test";
import assert from "node:assert/strict";
import { verdict } from "./deepseek-review.mjs";

test("recognized decisions include gates and markdown formatting",()=>{
  for(const decision of ["ACCEPT_WITH_GATES","ACCEPT_SCOPED","REQUEST_CHANGES","READY_FOR_INTEGRATION_VALIDATION"])
    assert.equal(verdict(`## **${decision}**\n意见\n本轮必须修改项：无`).conclusion,decision);
});
test("missing decision is not consensus",()=>{
  assert.equal(verdict("仍需进一步分析").conclusion,null);
  assert.equal(verdict("ACCEPT_SCOPED\n本轮必须修改项：补充取消验证").mustFixesClear,false);
});
