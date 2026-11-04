export function filterTemplates(list, keyword) {
    const query = String(keyword || '').trim().toLocaleLowerCase()
    return (list || []).filter(template => !query || `${template.name || ''} ${template.description || ''}`.toLocaleLowerCase().includes(query))
}
