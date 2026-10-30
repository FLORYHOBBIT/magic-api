// LogicFlow 适配：节点卡片、三种连线、流程定义 ⇄ LogicFlow 数据互转
import { HtmlNode, HtmlNodeModel, BezierEdge, BezierEdgeModel } from '@logicflow/core'
import { nodeType } from './node-types.js'

export const NODE_W = 170
export const NODE_H = 58
export const NODE = 'flow-node'
export const EDGE = 'flow-edge'

// 调试轨迹与已渲染的卡片，用于在不改节点数据的情况下刷新节点上的执行信息
const traces = { map: {} }
const cards = new Map()

const escapeHtml = text => String(text == null ? '' : text).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])

function renderCard(el, model) {
    const p = model.properties || {}
    const t = nodeType(p.flowType)
    const trace = traces.map[model.id]
    const state = trace ? (trace.success ? ' ok' : ' fail') : ''
    el.innerHTML = `<div class="mf-lf-node${t.trigger ? ' trigger' : ''}${state}" style="width:${NODE_W}px;height:${NODE_H}px">
        <div class="mf-lf-type" style="background:${t.color}">${escapeHtml(t.label)}${trace ? `<span>#${trace.step} ${trace.duration}ms</span>` : ''}</div>
        <div class="mf-lf-name" title="${escapeHtml(model.id)}">${escapeHtml(p.name || model.id)}</div>
    </div>`
}

export function setTraces(map) {
    traces.map = map || {}
    cards.forEach(({ el, model }) => renderCard(el, model))
}

class FlowNodeView extends HtmlNode {
    setHtml(rootEl) {
        cards.set(this.props.model.id, { el: rootEl, model: this.props.model })
        renderCard(rootEl, this.props.model)
    }

    componentWillUnmount() {
        cards.delete(this.props.model.id)
        super.componentWillUnmount && super.componentWillUnmount()
    }
}

class FlowNodeModel extends HtmlNodeModel {
    setAttributes() {
        this.width = NODE_W
        this.height = NODE_H
        this.text.editable = false
        this.text.value = ''
    }

    // 只能从「出」（右侧）或「失败」（底部）连到另一节点的「入」（左侧）
    getConnectedSourceRules() {
        return super.getConnectedSourceRules().concat([{
            message: '请从节点右侧（成功/条件）或底部（失败）圆点连到另一个节点左侧圆点',
            validate: (source, target, sourceAnchor, targetAnchor) =>
                !!sourceAnchor && sourceAnchor.kind !== 'in' && !!targetAnchor && targetAnchor.kind === 'in' && source.id !== target.id
        }])
    }

    getConnectedTargetRules() {
        return super.getConnectedTargetRules().concat([{
            message: '触发节点只能是起始节点，不能连入',
            validate: () => !nodeType(this.properties.flowType).trigger
        }])
    }

    getDefaultAnchor() {
        const t = nodeType(this.properties.flowType)
        const { x, y, id } = this
        const anchors = []
        if (!t.trigger) {
            anchors.push({ x: x - NODE_W / 2, y, id: `${id}_in`, kind: 'in' })
        }
        if (!t.terminal) {
            anchors.push({ x: x + NODE_W / 2, y, id: `${id}_out`, kind: 'out' })
            if (!t.trigger) {
                anchors.push({ x, y: y + NODE_H / 2, id: `${id}_fail`, kind: 'fail' })
            }
        }
        return anchors
    }

    getAnchorStyle(anchor) {
        const style = super.getAnchorStyle(anchor)
        style.r = anchor && anchor.kind === 'in' ? 4 : 5
        style.fill = anchor && anchor.kind === 'fail' ? '#E02424' : anchor && anchor.kind === 'out' ? '#2563EB' : '#94A3B8'
        style.stroke = '#fff'
        return style
    }

    getOutlineStyle() {
        const style = super.getOutlineStyle()
        style.stroke = '#2563EB'
        style.strokeDasharray = 'none'
        return style
    }
}

export const isFailure = c => c != null && String(c).trim() === 'failure'
export const isDefault = c => c == null || String(c).trim() === '' || String(c).trim() === 'success'
export const edgeKind = c => isFailure(c) ? 'failure' : isDefault(c) ? 'default' : 'condition'
export const edgeLabel = c => {
    const kind = edgeKind(c)
    if (kind === 'failure') return '失败'
    if (kind === 'default') return ''
    const s = String(c)
    return s.length > 24 ? s.substring(0, 24) + '…' : s
}

class FlowEdgeModel extends BezierEdgeModel {
    getEdgeStyle() {
        const style = super.getEdgeStyle()
        const kind = edgeKind(this.properties.condition)
        style.stroke = kind === 'failure' ? '#E02424' : kind === 'condition' ? '#D97706' : '#64748B'
        style.strokeWidth = this.isSelected ? 3 : 1.6
        if (kind === 'failure') {
            style.strokeDasharray = '5 3'
        }
        return style
    }

    getTextStyle() {
        const style = super.getTextStyle()
        style.color = edgeKind(this.properties.condition) === 'failure' ? '#E02424' : '#D97706'
        style.fontSize = 11
        style.background = { fill: 'transparent' }
        return style
    }

    getOutlineStyle() {
        const style = super.getOutlineStyle()
        style.stroke = 'transparent'
        return style
    }
}

export function register(lf) {
    lf.register({ type: NODE, view: FlowNodeView, model: FlowNodeModel })
    lf.register({ type: EDGE, view: BezierEdge, model: FlowEdgeModel })
    lf.setDefaultEdgeType(EDGE)
}

/**
 * 流程定义 → LogicFlow 数据（流程中 ui.x/y 为左上角，LogicFlow 为中心点）
 */
export function toGraph(def) {
    const nodes = (def.nodes || []).map((n, i) => {
        const ui = n.ui && typeof n.ui.x === 'number' ? n.ui : { x: 60 + (i % 5) * 220, y: 80 + Math.floor(i / 5) * 120 }
        return {
            id: n.id,
            type: NODE,
            x: ui.x + NODE_W / 2,
            y: ui.y + NODE_H / 2,
            properties: { flowType: n.type, name: n.name || '', config: n.config && typeof n.config === 'object' ? n.config : {}, ui: { ...ui } }
        }
    })
    const ids = new Set(nodes.map(n => n.id))
    const edges = (def.edges || []).filter(e => ids.has(e.source) && ids.has(e.target)).map(e => ({
        type: EDGE,
        sourceNodeId: e.source,
        targetNodeId: e.target,
        sourceAnchorId: isFailure(e.condition) ? `${e.source}_fail` : `${e.source}_out`,
        targetAnchorId: `${e.target}_in`,
        properties: e.condition == null ? {} : { condition: e.condition },
        text: edgeLabel(e.condition)
    }))
    return { nodes, edges }
}

/**
 * LogicFlow 数据 → 流程定义；节点、连线顺序保持（条件连线按顺序计算）
 */
export function fromGraph(data) {
    const nodes = (data.nodes || []).map(n => {
        const p = n.properties || {}
        const node = { id: n.id, type: p.flowType, name: p.name || undefined, config: p.config || {} }
        node.ui = { ...(p.ui || {}), x: Math.round(n.x - NODE_W / 2), y: Math.round(n.y - NODE_H / 2) }
        if (!node.name) delete node.name
        return node
    })
    const edges = (data.edges || []).map(e => {
        const edge = { source: e.sourceNodeId, target: e.targetNodeId }
        const condition = e.properties && e.properties.condition
        if (condition != null && String(condition).trim() !== '') {
            edge.condition = condition
        }
        return edge
    })
    return { nodes, edges }
}
